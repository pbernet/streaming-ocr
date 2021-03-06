import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.FileInfo
import akka.stream.scaladsl.{FileIO, Flow, Sink, Source, StreamConverters}
import akka.{Done, NotUsed}
import com.joestelmach.natty.Parser
import com.recognition.software.jdeskew.{ImageDeskew, ImageUtil}
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.util.ImageHelper
import opennlp.tools.namefind.{NameFinderME, TokenNameFinderModel}
import opennlp.tools.sentdetect.{SentenceDetectorME, SentenceModel}
import opennlp.tools.tokenize.{TokenizerME, TokenizerModel}
import opennlp.tools.util.Span
import org.bytedeco.javacpp.indexer.UByteRawIndexer
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacv.Java2DFrameUtils
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import java.awt.image.BufferedImage
import java.io.{File, FileInputStream}
import java.nio.file.Paths
import javax.imageio.ImageIO
import scala.collection.parallel.CollectionConverters._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

/**
 * Start [[StreamingUploadClient]] to generate traffic via HTTP
 * or comment in method `parallelizationPoC` to run locally
 *
 */
object Main extends App with OCR with Spell with NLP with Natty {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  implicit val system: ActorSystem = ActorSystem("ocr")
  implicit val executor: ExecutionContextExecutor = system.dispatcher
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()
  import MyJsonProtocol._

  def imageDeSkew(skewThreshold:Double = 0.050) = Flow[BufferedImage].map(bi => {
    val deSkew = new ImageDeskew(bi)
    val imageSkewAngle = deSkew.getSkewAngle

    if (imageSkewAngle > skewThreshold || imageSkewAngle < -skewThreshold) {
      ImageUtil.rotate(bi, -imageSkewAngle, bi.getWidth() / 2, bi.getHeight() / 2)
    } else {
      bi
    }
  })

  def imageToBinaryImage = Flow[BufferedImage].map(img => {
    val bin = ImageHelper.convertImageToBinary(img)
    bin
  })

  def bufferedImageToMat = Flow[BufferedImage].map(bi => {
    val mat = new Mat(bi.getHeight, bi.getWidth, CV_8UC(3))
    val indexer:UByteRawIndexer = mat.createIndexer()
    for (y <- 0 until bi.getHeight()) {
      for (x <- 0 until bi.getWidth()) {
        val rgb = bi.getRGB(x, y)
        indexer.put(y, x, 0, (rgb >> 0) & 0xFF)
        indexer.put(y, x, 1, (rgb >> 8) & 0xFF)
        indexer.put(y, x, 2, (rgb >> 16) & 0xFF)
      }
    }
    indexer.release()
    mat
  })

  def matToBufferedImage = Flow[Mat].map(mat => {
    Java2DFrameUtils.toBufferedImage(mat)
  })

  import org.bytedeco.javacpp.{opencv_photo => Photo}
  def enhanceMat = Flow[Mat].map(mat => {
    val src = mat.clone()
    Photo.fastNlMeansDenoising(mat, src, 40, 7, 21)
    val dst = src.clone()
    Photo.detailEnhance(src, dst)
    dst
  })

  def extractPersons = Flow[OcrSuggestions].map(ocr => {
    val tokens = tokenizer.tokenize(ocr.ocr)
    val spans:Array[Span] = personFinderME.find(tokens)
    val persons = spans.toList.map(span => tokens(span.getStart()))
    OcrSuggestionsPersons(ocr.ocr, ocr.suggestions, persons)
  })

  def extractDates = Flow[OcrSuggestionsPersons].map(ocr => {
    val sentences = sentenceDetector.sentDetect(ocr.ocr.replaceAll("\n", " ")).toList

    val dates = sentences.map(sentence => parser.parse(sentence))
      .flatMap(dateGroups => dateGroups.asScala.toList)
      .map(dateGroup => (dateGroup.getDates().asScala.toList.map(_.toString()), dateGroup.getText()))

    OcrSuggestionsPersonsDates(ocr.ocr, ocr.suggestions, ocr.persons, dates)
  })

  def spellCheck = Flow[String].map(ocr => {
    logger.info(s"Before spellCheck: $ocr")
    val words: Set[String] = ocr.replaceAll("-\n", "").replaceAll("\n", " ").replaceAll("-"," ").split("\\s+")
      .map(_.replaceAll(
      "[^a-zA-Z'’\\d\\s]", "") // Remove most punctuation
      .trim)
      .filter(!_.isEmpty).toSet
      logger.info(s"words: $words")
    val misspelled = words.filter(word => !speller.isCorrect(word))
    val suggestions: Set[Map[String, List[String]]] = misspelled.map(mis => {
      Map(mis -> speller.suggest(mis).asScala.toList)
    })
    OcrSuggestions(ocr, suggestions)
  })

  // It seems as if tesseract is not able to run things in parallel, there is no performance gain
  val numberOfCores: Int = Runtime.getRuntime.availableProcessors
  def imageOcr = Flow[BufferedImage].mapAsync(1)(each => Future(tesseract().doOCR(each)))

  def imageSink(path:String, format:String = "png") = Sink.foreachAsync[BufferedImage](4)(bi => {
    Future(ImageIO.write(bi, format, new File(path)))
  })

  val imageEnhance = bufferedImageToMat.via(enhanceMat).via(matToBufferedImage)

  val imagePreProcessFlow =
    imageToBinaryImage.alsoTo(imageSink("binary.png"))
    .via(imageEnhance).alsoTo(imageSink("enhanced.png"))
    .via(imageDeSkew()).alsoTo(imageSink("deskew.png"))

  val ocrFlow: Flow[BufferedImage, OcrSuggestionsPersons, NotUsed] = imageOcr.via(spellCheck).via(extractPersons)

  val staticResources =
    get {
      (pathEndOrSingleSlash & redirectToTrailingSlashIfMissing(StatusCodes.TemporaryRedirect)) {
        getFromResource("public/index.html")
      } ~ {
        getFromResourceDirectory("public")
      }
    }

  val route = path("image" / "ocr") {
    post {

      def tempDestination(fileInfo: FileInfo): File = File.createTempFile(fileInfo.fileName, ".tmp.server")

      //TODO fileUpload hangs with latest akka http version, revert to tmp storing
      //However, this is not ideal for the throughput...
      storeUploadedFile("fileUpload", tempDestination) {
        case (_, uploadedFile: File) =>
          logger.info(s"Stored uploaded tmp file: ${uploadedFile.getName}")

          //TODO Handle retry
          val hapiFlow = Flow[OcrSuggestionsPersons]
            .mapAsync(2)(each => {
              HapiClient.prepareAndUpload(each, new FileInputStream(uploadedFile))
              Future(each)
            })


          val inputStream = FileIO.fromPath(uploadedFile.toPath).runWith(StreamConverters.asInputStream())
          val image = ImageIO.read(inputStream)
          val ocr = Source.single(image)
            .via(imagePreProcessFlow)
            .via(ocrFlow)
            .via(hapiFlow)
          complete(ocr)
      }
    }
  } ~ staticResources

  def logWhen(done: Future[Done], each: Int) = {
    done.onComplete {
      case Success(_) =>
        logger.info(s"Success: $each")
      case Failure(e) =>
        logger.error(s"Failure: ${e.getMessage}")
    }
  }

  val resourceFileName = "test_ocr.jpg"
  val (address, port) = ("127.0.0.1", 8080)

  private def parallelizationPoC() = {
    (1 to 10).par.foreach(each => {
      logger.info(s"Start processing: $each")
      val image = ImageIO.read(Paths.get(s"src/main/resources/$resourceFileName").toFile)
      val ocr = Source.single(image).via(imagePreProcessFlow).via(ocrFlow)
      val done: Future[Done] = ocr.runWith(Sink.ignore)
      logWhen(done, each)
    })
  }

  // PoC to try parallelization
  //parallelizationPoC()

  val bindingFuture = Http().newServerAt("localhost", 8080).bindFlow(route)
  bindingFuture.onComplete {
    case Success(b) =>
      logger.info("Server started, listening on: " + b.localAddress)
    case Failure(e) =>
      logger.info(s"Server could not bind to localhost:8080. Exception message: ${e.getMessage}")
      system.terminate()
  }
}

case class OcrSuggestions(ocr:String, suggestions: Set[Map[String, List[String]]])
case class OcrSuggestionsPersons(ocr:String, suggestions: Set[Map[String, List[String]]], persons: List[String])
case class OcrSuggestionsPersonsDates(ocr:String, suggestions: Set[Map[String, List[String]]], persons: List[String], dates: List[(List[String], String)])

trait OCR {

  // We need separate "Tesseract contexts" hence def
  def tesseract(): Tesseract = {
    val tess = new Tesseract
    // On mac: brew install|upgrade tesseract
    tess.setDatapath("/usr/local/Cellar/tesseract/4.1.1/share/tessdata/")
    // Fallback if the resolution info is not in the metadata
    tess.setTessVariable("user_defined_dpi","300")
    tess
  }
}

trait Spell {
  import com.atlascopco.hunspell.Hunspell
  lazy val speller = new Hunspell("src/main/resources/en_US.dic", "src/main/resources/en_US.aff")
}

trait NLP {
  lazy val tokenModel = new TokenizerModel(getClass.getResourceAsStream("/en-token.bin"))
  lazy val tokenizer = new TokenizerME(tokenModel);

  lazy val sentenceModel = new SentenceModel(getClass.getResourceAsStream("/en-sent.bin"))
  lazy val sentenceDetector = new SentenceDetectorME(sentenceModel);

  lazy val personModel = new TokenNameFinderModel(getClass.getResourceAsStream("/en-ner-person.bin"))
  lazy val personFinderME = new NameFinderME(personModel);
}

trait Natty {
  lazy val parser = new Parser()
}

object MyJsonProtocol
  extends SprayJsonSupport
    with DefaultJsonProtocol {
  implicit val ocrFormat: RootJsonFormat[OcrSuggestions] = jsonFormat2(OcrSuggestions.apply)
  implicit val ocr2Format: RootJsonFormat[OcrSuggestionsPersons] = jsonFormat3(OcrSuggestionsPersons.apply)
  implicit val ocr3Format: RootJsonFormat[OcrSuggestionsPersonsDates] = jsonFormat4(OcrSuggestionsPersonsDates.apply)
}
