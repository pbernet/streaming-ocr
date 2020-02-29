import java.io.File
import java.nio.file.Paths

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.{HttpRequest, RequestEntity, _}
import akka.stream.scaladsl.{FileIO, Source}
import org.slf4j.{Logger, LoggerFactory}
import spray.json.DefaultJsonProtocol

import scala.concurrent.Future
import scala.sys.process.Process
import scala.util.{Failure, Success}

object StreamingUploadClient extends App with DefaultJsonProtocol with SprayJsonSupport {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)
  implicit val system = ActorSystem("StreamingUploadClient")
  implicit val executionContext = system.dispatcher

  val resourceFileName = "test_ocr.jpg"
  val (address, port) = ("127.0.0.1", 8080)

  uploadClient(address, port)
  browserClient(address, port)

  def uploadClient(address: String, port: Int) = {

    def filesToUpload(): Source[File, NotUsed] =
      //Unbounded stream. Limit for testing purposes by appending eg .take(10)
      Source(Stream.continually(Paths.get(s"./src/main/resources/$resourceFileName").toFile)).take(10)

    val poolClientFlowUpload =
      Http().cachedHostConnectionPool[File](address, port)

    def createUploadRequest(fileToUpload: File): Future[(HttpRequest, File)] = {
      val bodyPart =
        FormData.BodyPart.fromPath("fileUpload", ContentTypes.`application/octet-stream`, Paths.get(fileToUpload.getAbsolutePath))

      val body = FormData(bodyPart) // only one file per upload
      Marshal(body).to[RequestEntity].map { entity => // use marshalling to create multipart/formdata entity
        val target = Uri(s"http://$address:$port").withPath(akka.http.scaladsl.model.Uri.Path("/image/ocr"))
        HttpRequest(method = HttpMethods.POST, uri = target, entity = entity) -> fileToUpload
      }
    }

    filesToUpload()
      .mapAsync(1)(createUploadRequest)
      // throttled by max-connections in application.conf
      .via(poolClientFlowUpload)
      // responses will NOT come in in the same order as requests
      .runForeach {
      case (Success(response: HttpResponse), fileToUpload) =>
        logger.info(s"Upload for file: $fileToUpload was successful: ${response.status}")
        val localFile = File.createTempFile("downloadLocal", ".tmp.client")
        val result = response.entity.dataBytes.runWith(FileIO.toPath(Paths.get(localFile.getAbsolutePath)))
        result.map {
          ioresult =>
            logger.info(s"Download file: ${localFile.getAbsoluteFile} finished (${ioresult.count} bytes)")
            val fileSource = scala.io.Source.fromFile(localFile)
            logger.info("Payload: " + fileSource.getLines.mkString)
            fileSource.close()
        }
      case (Failure(ex), fileToUpload) =>
        logger.info(s"Uploading file: $fileToUpload failed with: $ex")
    }
  }

  def browserClient(address: String, port: Int) = {
    val os = System.getProperty("os.name").toLowerCase
    if (os == "mac os x") Process(s"open http://$address:$port").!
  }
}