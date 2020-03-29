import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.apache.commons.text.StringEscapeUtils;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;


/**
 * Upload FHIR document (and observation) HAPI server:
 * http://hapi.fhir.org/baseR4
 * and link to patient
 * <p>
 *
 * TODOs
 * - Handle various "not happy paths"
 * - Feedback on GUI about the processing stages
 * - Search: Feed OCR content to elasticsearch
 **/
public class HapiClient {
    private static final Logger logger = LoggerFactory.getLogger(HapiClient.class);

    public static final byte[] SOME_BYTES = {1, 2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3, 2, 1};

    public static final FhirContext ctx = FhirContext.forR4();

    private static DocumentReference createDocumentReference(Patient patient, String ocrText, byte[] picBase64) {
        DocumentReference documentReference = new DocumentReference();
        documentReference.setSubject(new Reference(patient.getIdElement().getValue()));

        Narrative narrative = new Narrative();
        narrative.setDivAsString(StringEscapeUtils.escapeHtml4(ocrText));
        documentReference.setText(narrative);

        Attachment attachment = documentReference
                .addContent()
                .getAttachment()
                .setContentType("image/jpg");
        attachment.setData(picBase64);

        logger.info(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(documentReference));
        return documentReference;
    }

    private static IIdType writeDocumentReference(DocumentReference documentReference) {
        IGenericClient client = ctx.newRestfulGenericClient("http://hapi.fhir.org/baseR4");
        return client.create().resource(documentReference).execute().getId().toUnqualifiedVersionless();
    }

    private static DocumentReference readDocumentReference(IIdType id) {
        IGenericClient client = ctx.newRestfulGenericClient("http://hapi.fhir.org/baseR4");

        //Verify by reading. TODO find out on attributes to compare
        DocumentReference doc = client.read().resource(DocumentReference.class).withId(id).execute();
        logger.info("Read doc with ID: " + doc.getId());
        return doc;

    }


    public static boolean prepareAndUpload(OcrSuggestionsPersons ocrSuggestionsPersons, String pathToOrigFile) {
        String identifierBusiness = "ocrTestPatient";

        Patient patient = createTestPatient(identifierBusiness);

        Path path = Paths.get(pathToOrigFile);
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            Base64.Encoder enc = Base64.getEncoder();
            DocumentReference doc = createDocumentReference(patient, ocrSuggestionsPersons.ocr(), enc.encode(fis.readAllBytes()));

            // Create a bundle that will be used as a transaction
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.TRANSACTION);

            bundle.addEntry()
                    .setFullUrl(patient.getIdElement().getValue())
                    .setResource(patient)
                    .getRequest()
                    .setUrl("Patient")
                    .setIfNoneExist("identifier=http://acme.org/mrns|" + identifierBusiness)
                    .setMethod(Bundle.HTTPVerb.POST);

            bundle.addEntry()
                    .setResource(doc)
                    .getRequest()
                    .setUrl("DocumentReference")
                    .setMethod(Bundle.HTTPVerb.POST);

            // Log the request. Be aware of the base64 content
            logger.debug(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle));

            // Create a client and post the transaction to the server
            IGenericClient client = ctx.newRestfulGenericClient("http://hapi.fhir.org/baseR4");
            Bundle resp = client.transaction().withBundle(bundle).execute();

            // Log the response
            logger.info(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(resp));
            //TODO Find a better criteria, and handle not happy path
            return resp != null;
        } catch (IOException ioe) {
            System.err.printf("I/O error: %s%n", ioe.getMessage());
        }

        return false;
    }

    //PoC
    public static void main(String[] args) {

        String identifierBusiness = "aBusinessID";
        Patient patient = createTestPatient(identifierBusiness);
        Observation observation = createTestObservation(patient);
        DocumentReference doc = createDocumentReference(patient, "dummyOcrText", SOME_BYTES);


        // Create a bundle that will be used as a transaction
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        // Add the patient as an entry. This entry is a POST with an
        // If-None-Exist header (conditional create) meaning that it
        // will only be created if there isn't already a Patient with
        // the identifier identifierBusiness
        bundle.addEntry()
                .setFullUrl(patient.getIdElement().getValue())
                .setResource(patient)
                .getRequest()
                .setUrl("Patient")
                .setIfNoneExist("identifier=http://acme.org/mrns|" + identifierBusiness)
                .setMethod(Bundle.HTTPVerb.POST);

        // Add the observation. This entry is a POST with no header
        // (normal create) meaning that it will be created even if
        // a similar resource already exists.
        bundle.addEntry()
                .setResource(observation)
                .getRequest()
                .setUrl("Observation")
                .setMethod(Bundle.HTTPVerb.POST);

        // Add DocumentReference
        bundle.addEntry()
                .setResource(doc)
                .getRequest()
                .setUrl("DocumentReference")
                .setMethod(Bundle.HTTPVerb.POST);


        // Log the request
        System.out.println(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle));

        // Create a client and post the transaction to the server
        IGenericClient client = ctx.newRestfulGenericClient("http://hapi.fhir.org/baseR4");
        Bundle resp = client.transaction().withBundle(bundle).execute();

        // Log the response
        System.out.println(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(resp));

        //TODO How to get the doc id from the bundle response to check?

        System.out.println("Response 1st element id: " + resp.getEntry().get(0).getLink());
    }

    private static Observation createTestObservation(Patient patient) {
        // Create an observation object
        Observation observation = new Observation();
        observation.setStatus(Observation.ObservationStatus.FINAL);
        observation
                .getCode()
                .addCoding()
                .setSystem("http://loinc.org")
                .setCode("789-8")
                .setDisplay("Erythrocytes [#/volume] in Blood by Automated count");
        observation.setValue(
                new Quantity()
                        .setValue(4.12)
                        .setUnit("10 trillion/L")
                        .setSystem("http://unitsofmeasure.org")
                        .setCode("10*12/L"));

        // The observation refers to the patient using the ID, which is already
        // set to a temporary UUID
        observation.setSubject(new Reference(patient.getIdElement().getValue()));
        return observation;
    }

    private static Patient createTestPatient(String identifierBusiness) {
        // Create a patient object
        Patient patient = new Patient();
        patient.addIdentifier()
                .setSystem("http://acme.org/mrns")
                .setValue(identifierBusiness);
        patient.addName()
                .addGiven("J")
                .addGiven("Jonah");

        // Give the patient a temporary UUID so that other resources in
        // the transaction can refer to it
        patient.setId(IdDt.newRandomUuid());
        return patient;
    }
}
