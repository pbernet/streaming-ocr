## PoC for parallelization

Additional features of this fork:
* Bumped libs
* PoC for parallelization
* Assuming the ocr contains medical content: upload to FHIR R4 server: http://hapi.fhir.org/baseR4

Tmp removed feature:
* Date extraction via Natty parser

The two main classes may be started directly from the IntelliJ IDE.
[GraalVM](https://www.graalvm.org/downloads) was used to run it locally on Mac.


## Up and running | Mac OS

[OCR with Akka, Tesseract, and JavaCV | Part 1](https://towardsdatascience.com/ocr-with-akka-tesseract-and-javacv-part-1-702781fc73ca)
[OCR with Akka, Tesseract, and JavaCV | Part 2](https://towardsdatascience.com/ocr-with-akka-tesseract-and-javacv-part-1-702781fc73ca)

```
brew install tesseract
```

And then I set an Environment Var `LC_ALL=C` in IntelliJ Run Configuration

> Didn't need this env var in VSCode

```
sbt run
```

Open http://localhost:8080 and upload an image.

Or, using cURL:

```
curl -X POST -F 'fileUpload=@/Users/duanebester/Documents/input.jpg' 'http://localhost:8080/image/ocr'
```