package hudson.plugins.testng.parser;

import hudson.plugins.testng.results.ClassResult;
import hudson.plugins.testng.results.MethodResult;
import hudson.plugins.testng.results.MethodResultException;
import hudson.plugins.testng.results.TestResult;
import hudson.plugins.testng.results.TestResults;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Parses testng result XMLs generated using org.testng.reporters.XmlReporter
 * into objects that are then used to display results in Jenkins
 *
 * @author nullin
 */
public class ResultsParser {

   private static Logger log = Logger.getLogger(ResultsParser.class.getName());
   public static String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
   private SimpleDateFormat simpleDateFormat = new SimpleDateFormat(DATE_FORMAT);

   /*
    * We maintain only a single TestResult for all <test>s with the same name
    */
   private Map<String, TestResult> testResultMap = new HashMap<String, TestResult>();
   /*
    * We maintain only a single ClassResult for all <class>s with the same fqdn
    */
   private Map<String, ClassResult> classResultMap = new HashMap<String, ClassResult>();
   private TestResults finalResults;
   private List<TestResult> testList;
   private List<ClassResult> currentClassList;
   private List<MethodResult> currentMethodList;
   private List<String> currentMethodParamsList;
   private TestResult currentTest;
   private ClassResult currentClass;
   private String currentTestRunId;
   private MethodResult currentMethod;
   private XmlPullParser xmlPullParser;
   private TAGS currentCDATAParent = TAGS.UNKNOWN;
   private String currentMessage;
   private String currentShortStackTrace;
   private String currentFullStackTrace;

   private enum TAGS {
     TESTNG_RESULTS, SUITE, TEST, CLASS, TEST_METHOD,
     PARAMS, PARAM, VALUE, EXCEPTION, UNKNOWN, MESSAGE, SHORT_STACKTRACE, FULL_STACKTRACE;

     public static TAGS fromString(String val) {
        if (val == null) {
           return UNKNOWN;
        }
        val = val.toUpperCase().replace('-', '_');
        try {
           return TAGS.valueOf(val);
        } catch (IllegalArgumentException e) {
           return UNKNOWN;
        }
     }
   }

   /**
    * Parses the XML for relevant information
    *
    * @param file a file hopefully containing test related data in correct format
    * @return a collection of test results
    */
   public TestResults parse(File file) {
      finalResults = new TestResults(UUID.randomUUID().toString() + "_TestResults");
      if (null == file) {
         log.severe("File not specified");
         return finalResults;
      }

      if (!file.exists() || file.isDirectory()) {
        log.severe("'" + file.getAbsolutePath() + "' points to a non-existent file or directory");
         return finalResults;
      }

      BufferedInputStream bufferedInputStream = null;
      try {
         bufferedInputStream = new BufferedInputStream(new FileInputStream(file));
         xmlPullParser = createXmlPullParser(bufferedInputStream);

         //some initial setup
         testList = new ArrayList<TestResult>();

         while (XmlPullParser.END_DOCUMENT != xmlPullParser.nextToken()) {
            TAGS tag = TAGS.fromString(xmlPullParser.getName());
            int eventType = xmlPullParser.getEventType();

            switch (eventType) {
              //all opening tags
               case XmlPullParser.START_TAG:
                  switch (tag) {
                     case TEST:
                        startTest(get("name"));
                        break;
                     case CLASS:
                        startClass(get("name"));
                        break;
                     case TEST_METHOD:
                        startTestMethod(get("name"), get("status"),
                                 get("description"), get("duration-ms"),
                                 get("started-at"), get("is-config"));
                        break;
                     case PARAMS:
                        startMethodParameters();
                        currentCDATAParent = TAGS.PARAMS;
                        break;
                     case EXCEPTION:
                        startException();
                        break;
                     case MESSAGE:
                        currentCDATAParent = TAGS.MESSAGE;
                        break;
                     case SHORT_STACKTRACE:
                        currentCDATAParent = TAGS.SHORT_STACKTRACE;
                        break;
                     case FULL_STACKTRACE:
                        currentCDATAParent = TAGS.FULL_STACKTRACE;
                        break;
                  }
                  break;
               // all closing tags
               case XmlPullParser.END_TAG:
                  switch (tag) {
                     case TEST:
                        finishTest();
                        break;
                     case CLASS:
                        finishclass();
                        break;
                     case TEST_METHOD:
                        finishTestMethod();
                        break;
                     case PARAMS:
                        finishMethodParameters();
                        currentCDATAParent = TAGS.UNKNOWN;
                        break;
                     case EXCEPTION:
                        finishException();
                        break;
                     case MESSAGE:
                     case SHORT_STACKTRACE:
                     case FULL_STACKTRACE:
                        currentCDATAParent = TAGS.UNKNOWN;
                        break;
                  }
                  break;
               // all cdata reading
               case XmlPullParser.CDSECT:
                  handleCDATA();
                  break;
            }
         }
         finalResults.addTestList(testList);
      } catch (XmlPullParserException e) {
         log.warning("Failed to parse XML: " + e.getMessage());
         e.printStackTrace();
      } catch (FileNotFoundException e) {
        log.log(Level.SEVERE, "Failed to find XML file", e);
      } catch (IOException e) {
        log.log(Level.SEVERE, "Failed due to IOException while parsing XML file", e);
      } finally {
         try {
           if (bufferedInputStream != null) {
              bufferedInputStream.close();
           }
         } catch (IOException e) {
           log.log(Level.WARNING, "Failed to close input stream", e);
         }
      }

      return finalResults;
   }

   private void startException()
   {
      // do nothing (here for symmetry)
   }

   private void finishException()
   {
      MethodResultException mrEx = new MethodResultException(currentMessage,
               currentShortStackTrace, currentFullStackTrace);
      currentMethod.setException(mrEx);

      mrEx = null;
      currentMessage = null;
      currentShortStackTrace = null;
      currentFullStackTrace = null;
   }

   private void startMethodParameters()
   {
      currentMethodParamsList = new ArrayList<String>();
   }

   private void finishMethodParameters()
   {
      currentMethod.setParameters(currentMethodParamsList);
      currentMethodParamsList = null;
   }

   private void handleCDATA()
   {
      switch (currentCDATAParent) {
         case PARAMS:
            currentMethodParamsList.add(xmlPullParser.getText());
            break;
         case MESSAGE:
            currentMessage = xmlPullParser.getText();
            break;
         case FULL_STACKTRACE:
            currentFullStackTrace = xmlPullParser.getText();
            break;
         case SHORT_STACKTRACE:
            currentShortStackTrace = xmlPullParser.getText();
            break;
         case UNKNOWN:
            //do nothing
      }
   }

   private void startTestMethod(String name,
            String status,
            String description,
            String duration,
            String startedAt,
            String isConfig)
   {
      currentMethod = new MethodResult();
      currentMethod.setName(name);
      currentMethod.setStatus(status);
      currentMethod.setDescription(description);

      try {
         currentMethod.setDuration(Long.parseLong(duration));
      } catch (NumberFormatException e) {
         log.warning("Unable to parse duration value: " + duration);
      }

      try {
         currentMethod.setStartedAt(simpleDateFormat.parse(startedAt));
      } catch (ParseException e) {
         log.warning("Unable to parse started-at value: " + startedAt);
      }

      if (isConfig != null) {
         /*
          * If is-config attribute is present on test-method,
          * it's always set to true
          */
         currentMethod.setConfig(true);
      }

      //TODO: Need better handling of test run and method UUIDs
      String testUuid = UUID.randomUUID().toString();
      currentMethod.setTestUuid(testUuid);
      //this uuid is used later to group the tests and config-methods together
      currentMethod.setTestRunId(currentTestRunId);
   }

   private void finishTestMethod()
   {
      updateTestMethodLists(currentMethod);
      // add to test methods list for each class
      currentMethodList.add(currentMethod);

      currentMethod = null;
   }

   private void startClass(String name)
   {
      if (classResultMap.containsKey(name)) {
         currentClass = classResultMap.get(name);
      } else {
         currentClass = new ClassResult(name);
         classResultMap.put(name, currentClass);
      }
      currentMethodList = new ArrayList<MethodResult>();
      //reset for each class
      currentTestRunId = UUID.randomUUID().toString();
   }

   private void finishclass()
   {
      currentClass.addTestMethods(currentMethodList);
      currentClassList.add(currentClass);

      currentMethodList = null;
      currentClass = null;
      currentTestRunId = null;
   }

   private void startTest(String name)
   {
      if (testResultMap.containsKey(name)) {
         currentTest = testResultMap.get(name);
      } else {
         currentTest = new TestResult(name);
         testResultMap.put(name, currentTest);
      }
      currentClassList = new ArrayList<ClassResult>();
   }

   private void finishTest()
   {
      currentTest.addClassList(currentClassList);
      testList.add(currentTest);

      currentClassList = null;
      currentTest = null;
   }

   private void updateTestMethodLists(MethodResult testNGTestMethod) {
      if (testNGTestMethod.isConfig()) {
         if ("FAIL".equals(testNGTestMethod.getStatus())) {
            finalResults.getFailedConfigurationMethods().add(testNGTestMethod);
         } else if ("SKIP".equals(testNGTestMethod.getStatus())) {
            finalResults.getSkippedConfigurationMethods().add(testNGTestMethod);
         }
      } else {
         if ("FAIL".equals(testNGTestMethod.getStatus())) {
            finalResults.getFailedTests().add(testNGTestMethod);
         } else if ("SKIP".equals(testNGTestMethod.getStatus())) {
            finalResults.getSkippedTests().add(testNGTestMethod);
         } else if ("PASS".equals(testNGTestMethod.getStatus())) {
            finalResults.getPassedTests().add(testNGTestMethod);
         }
      }
   }

   private String get(String attr)
   {
      return xmlPullParser.getAttributeValue(null, attr);
   }

   private XmlPullParser createXmlPullParser(BufferedInputStream
            bufferedInputStream) throws XmlPullParserException {
      XmlPullParserFactory xmlPullParserFactory = XmlPullParserFactory.newInstance();
      xmlPullParserFactory.setNamespaceAware(true);
      xmlPullParserFactory.setValidating(false);

      XmlPullParser xmlPullParser = xmlPullParserFactory.newPullParser();
      xmlPullParser.setInput(bufferedInputStream, null);
      return xmlPullParser;
   }
}
