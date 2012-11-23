/***************************************************************************
 * Copyright (C) 2009 Atlas of Living Australia
 * All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 ***************************************************************************/
package org.ala.harvester;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.ala.documentmapper.DocumentMapper;
import org.ala.documentmapper.MappingUtils;
import org.ala.repository.ParsedDocument;
import org.ala.repository.Predicates;
import org.ala.repository.Repository;
import org.ala.repository.Triple;
import org.ala.util.WebUtils;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * A Harvester class for Morphbank. 
 * 
 * This is a port from the Diasb codebase which is hideously over-engineered.
 * 
 * @author Tommy Wang
 */
@Component("MorphbankHarvester")
@Scope(BeanDefinition.SCOPE_PROTOTYPE) 
public class MorphbankHarvester implements Harvester {

    protected Logger logger = Logger.getLogger(MorphbankHarvester.class);

    protected String endpoint;
    //	private String eolGroupId;
    //	private String MorphbankRestBaseUrl;
    //	private String MorphbankApiKey;
    //	private int recordsPerPage;
    protected Repository repository;
    protected int timeGap = 0;
    private static final int RESULT_LIMIT = 8297;
//    private static final int RESULT_LIMIT = 1211;
    private static final int MORPHBANK_INFOSOURCE_ID = 1062;
    protected String contentType = "text/xml";
    protected int init = 1;
    protected boolean isCoral = false;

    public int getInit() {
        return init;
    }

    public void setInit(int init) {
        this.init = init;
    }

    /**
     * Main method for testing this particular Harvester
     *
     * @param args
     */
    public static void main(String[] args) throws Exception {
        String[] locations = {"classpath*:spring.xml"};
        ApplicationContext context = new ClassPathXmlApplicationContext(locations);
        MorphbankHarvester h = new MorphbankHarvester();
        Repository r = (Repository) context.getBean("repository"); 
        h.setRepository(r);

        //set the connection params	
        Map<String, String> connectParams = new HashMap<String, String>();
        
        if (args.length == 1 && args[0].equals("Coral")) { 
            h.setCoral(true);
            connectParams.put("endpoint", "http://morphbank-svc.ala.org.au/mb3/request?method=search&objecttype=Image&keywords=Coral+Reef+Research&limit=" + RESULT_LIMIT + "&firstResult=0&user=&group=&change=&lastDateChanged=&numChangeDays=1&id=&taxonName=&format=svc");
        } else if (args.length == 1) {
            connectParams.put("endpoint", "http://morphbank-svc.ala.org.au/mb3/request?method=search&objecttype=Image&keywords=Australia&limit=" + RESULT_LIMIT + "&firstResult=0&user=&group=&change=&lastDateChanged=&numChangeDays=1&id=&taxonName=&format=svc");
            try {
                h.setInit(Integer.valueOf(args[0]));  
            } catch (NumberFormatException nfe) {
                System.out.println("Starting id is not a number!");
                System.exit(1);
            }
        } else {
            connectParams.put("endpoint", "http://morphbank-svc.ala.org.au/mb3/request?method=search&objecttype=Image&keywords=Australia&limit=" + RESULT_LIMIT + "&firstResult=0&user=&group=&change=&lastDateChanged=&numChangeDays=1&id=&taxonName=&format=svc");
        }

        h.setConnectionParams(connectParams);
        h.start(MORPHBANK_INFOSOURCE_ID); 
    }	

    public boolean isCoral() {
        return isCoral;
    }

    public void setCoral(boolean isCoral) {
        this.isCoral = isCoral;
    }

    /**
     * @see org.ala.harvester.Harvester#setConnectionParams(java.util.Map)
     */
    @Override
    public void setConnectionParams(Map<String, String> connectionParams) {
        this.endpoint = connectionParams.get("endpoint");
    }


    @Override
    public void start(int infosourceId, int timeGap) throws Exception {
        this.timeGap = timeGap;
        start(infosourceId);
    }

    /**
     * @see org.ala.harvester.Harvester#start()
     */
    @Override
    public void start(int infosourceId) throws Exception {

        // TODO Auto-generated method stub
        Thread.sleep(timeGap);

        // Obtains the image listing on the page number specified.
        // Instance variable `currentResDom` will have new
        // DOM representation of the result.
        Document parsedDoc = getIndexPage();

        if (parsedDoc == null) {
            String errMsg = "DOM representation of image list XML has null reference.  ";
            logger.error(errMsg);
        }

        int resultNum = getResultNumber(parsedDoc);

        //				System.out.println(resultNum);

        if (resultNum > 0) {
            for (int counter = this.init; counter <= RESULT_LIMIT; counter++) {
                try {
                    processSingleImage(infosourceId, counter, parsedDoc);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private int getResultNumber(Document currentResDom) throws Exception {
        int resultNum = 0;

        XPathFactory factory = XPathFactory.newInstance();
        XPath xpath = factory.newXPath();

        String xPathToResultNum = "/response/numMatches/text()";

        resultNum = Integer.valueOf((String) xpath.evaluate(xPathToResultNum, currentResDom,
                XPathConstants.STRING));

        return resultNum;
    }

    /**
     * Process a single image, do the document mapping etc
     * 
     * @param infosourceId
     * @param imageIndex
     * @param currentResDom
     * @throws Exception
     */
    private void processSingleImage(int infosourceId, int imageIndex, Document currentResDom) throws Exception {

        XPathFactory factory = XPathFactory.newInstance();
        XPath xpath = factory.newXPath();
        ParsedDocument pd = new ParsedDocument();
        ParsedDocument imageDoc = new ParsedDocument();

        String subject = MappingUtils.getSubject();

        String xPathToIdentifier = "/response/object[" + imageIndex + "]/detailPageUrl/text()";
        String xPathToScientificName = "/response/object[" + imageIndex + "]/ScientificName/text()";
        String xPathToLatitude = "/response/object[" + imageIndex + "]/DecimalLatitude/text()";
        String xPathToLongitude = "/response/object[" + imageIndex + "]/DecimalLongitude/text()";
        String xPathToImageUrl = "/response/object[" + imageIndex + "]/thumbUrl/text()";
        String xPathToLicense = "/response/object[" + imageIndex + "]/copyrightText/text()";
        String xPathToKingdom = "/response/object[" + imageIndex + "]/Kingdom/text()";
        String xPathToPhylum = "/response/object[" + imageIndex + "]/Phylum/text()";
        String xPathToClass = "/response/object[" + imageIndex + "]/Class/text()";
        String xPathToOrder = "/response/object[" + imageIndex + "]/Order/text()";
        String xPathToFamily = "/response/object[" + imageIndex + "]/Family/text()";
        String xPathToGenus = "/response/object[" + imageIndex + "]/Genus/text()";
        String xPathToSpecificEpithet = "/response/object[" + imageIndex + "]/SpecificEpithet/text()";
        String xPathToCountry = "/response/object[" + imageIndex + "]/Country/text()";
        String xPathToLocality = "/response/object[" + imageIndex + "]/Locality/text()";

        String identifier = null;
        String scientificName = null;
        String latitude = null;
        String longitude = null;
        String imageUrl = null;
        String license = null;
        String kingdom = null;
        String phylum = null;
        String klass = null;
        String order = null;
        String family = null;
        String genus = null;
        String specificEpithet = null;
        String country = null;
        String locality = null;

        try {
            identifier = (String) xpath.evaluate(xPathToIdentifier, currentResDom,
                    XPathConstants.STRING);
            scientificName = (String) xpath.evaluate(xPathToScientificName, currentResDom,
                    XPathConstants.STRING);
            latitude = (String) xpath.evaluate(xPathToLatitude, currentResDom,
                    XPathConstants.STRING);
            longitude = (String) xpath.evaluate(xPathToLongitude, currentResDom,
                    XPathConstants.STRING);
            imageUrl = (String) xpath.evaluate(xPathToImageUrl, currentResDom,
                    XPathConstants.STRING);
            license = (String) xpath.evaluate(xPathToLicense, currentResDom,
                    XPathConstants.STRING);
            kingdom = (String) xpath.evaluate(xPathToKingdom, currentResDom,
                    XPathConstants.STRING);
            phylum = (String) xpath.evaluate(xPathToPhylum, currentResDom,
                    XPathConstants.STRING);
            klass = (String) xpath.evaluate(xPathToClass, currentResDom,
                    XPathConstants.STRING);
            order = (String) xpath.evaluate(xPathToOrder, currentResDom,
                    XPathConstants.STRING);
            family = (String) xpath.evaluate(xPathToFamily, currentResDom,
                    XPathConstants.STRING);
            genus = (String) xpath.evaluate(xPathToGenus, currentResDom,
                    XPathConstants.STRING);
            specificEpithet = (String) xpath.evaluate(xPathToSpecificEpithet, currentResDom,
                    XPathConstants.STRING);
            country = (String) xpath.evaluate(xPathToCountry, currentResDom,
                    XPathConstants.STRING);
            locality = (String) xpath.evaluate(xPathToLocality, currentResDom,
                    XPathConstants.STRING);

        } catch (XPathExpressionException getPageFragmentationError) {
            String errMsg = "Failed to obtain Morphbank's Detail Page Url";
            logger.error(errMsg);
            throw new Exception(errMsg, getPageFragmentationError);
        }

        //		System.out.println("Index: " + imageIndex);

        identifier = identifier.replaceAll("\\-svc", "");
        System.out.println(imageIndex + ", PHOTO URL:" + identifier);

        List<Triple<String,String,String>> triples = pd.getTriples();
        Map<String, String> dcs = pd.getDublinCore();

        pd.setGuid(identifier);
        pd.setContent(getContent(identifier));
        pd.setContentType(contentType);

        dcs.put(Predicates.DC_TITLE.toString(), scientificName);
        dcs.put(Predicates.DC_IDENTIFIER.toString(), identifier);
        dcs.put(Predicates.LATITUDE.toString(), latitude);
        dcs.put(Predicates.LONGITUDE.toString(), longitude);
        dcs.put(Predicates.DC_LICENSE.toString(), "Creative Commons Attribution-Non Commercial 3.0 Australia License, http://creativecommons.org/licenses/by-nc/3.0/au/deed.en");
        dcs.put(Predicates.DC_CREATOR.toString(), license);
        if (isCoral) {
            dcs.put(Predicates.DC_RIGHTS.toString(), "J. Veron Coral Reef Research");
        } else {
            if (license != null && !"".equals(license)) {
                dcs.put(Predicates.DC_RIGHTS.toString(), license);
            } else {
                dcs.put(Predicates.DC_RIGHTS.toString(), "Copyright by Morphbank");
            }
        }
        dcs.put(Predicates.COUNTRY.toString(), country);
        dcs.put(Predicates.LOCALITY.toString(), locality);

        triples.add(new Triple(subject, Predicates.SCIENTIFIC_NAME.toString(), scientificName));
        triples.add(new Triple(subject, Predicates.KINGDOM.toString(), kingdom));
        triples.add(new Triple(subject, Predicates.PHYLUM.toString(), phylum));
        triples.add(new Triple(subject, Predicates.CLASS.toString(), klass));
        triples.add(new Triple(subject, Predicates.ORDER.toString(), order));
        triples.add(new Triple(subject, Predicates.FAMILY.toString(), family));
        triples.add(new Triple(subject, Predicates.GENUS.toString(), genus));
        triples.add(new Triple(subject, Predicates.SPECIFIC_EPITHET.toString(), specificEpithet));


        if (imageUrl != null && !"".equals(imageUrl)) {
            imageUrl = imageUrl.replaceAll("thumb", "jpg");
            imageUrl = imageUrl.replaceAll("images\\.morphbank\\.net", "morphbank-images.ala.org.au");
            imageDoc = MappingUtils.retrieveImageDocument(pd,imageUrl);
            //			debugParsedDoc(imageDoc);
        }

        //		debugParsedDoc(pd);

        if (pd != null) {
            this.repository.storeDocument(infosourceId, pd);
        }
        if (imageDoc != null) {
            this.repository.storeDocument(infosourceId, imageDoc);
        }
    } // End of `processSingleImage` method.

    private byte[] getContent(String url) throws Exception {
        String contentStr = null;

        // Create an instance of HttpClient.
//        HttpClient client = new HttpClient();
//
//        // Create a method instance.
//        GetMethod method = new GetMethod(url);
//
//        UsernamePasswordCredentials upc =
//            new UsernamePasswordCredentials("", "");
//        AuthScope as = new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT);
//        client.getState().setCredentials(as, upc);
//        method.setDoAuthentication(true);
//
//        // Provide custom retry handler is necessary
//        method.getParams().setParameter(HttpMethodParams.HTTP_CONTENT_CHARSET,"UTF-8");
//        method.getParams().setParameter(HttpMethodParams.HTTP_ELEMENT_CHARSET,"UTF-8");
//        method.getParams().setParameter(HttpMethodParams.HTTP_URI_CHARSET,"UTF-8");
//
//        try {
//            int statusCode = client.executeMethod(method);
//
//            if (statusCode != HttpStatus.SC_OK) {
//                String errMsg = "HTTP GET to " + "`" + url + "`"
//                + " returned non HTTP OK code.  " + "Returned code "
//                + statusCode + " and message " + method.getStatusLine()
//                + "\n";
//                method.releaseConnection();
//                logger.error(errMsg);
//                throw new Exception(errMsg);
//            }
//
//            InputStream responseStream = method.getResponseBodyAsStream();
//
//            contentStr = inputStream2String(responseStream);
//
//        } catch (Exception domCreationErr) {
//            throw new Exception(
//                    domCreationErr);
//
//        } finally {
//            // Release the connection.
//            method.releaseConnection();
//        }
        
        contentStr = WebUtils.getHTMLPageAsXML(url);
//        System.out.println(contentStr);

        return contentStr.getBytes();
    }

    private String inputStream2String(InputStream is) throws IOException{
        BufferedReader in = new BufferedReader(new InputStreamReader(is));
        StringBuffer buffer = new StringBuffer();
        String line = "";
        while ((line = in.readLine()) != null){
            buffer.append(line);
        }
        return buffer.toString();
    }

    private org.w3c.dom.Document getIndexPage() throws Exception {

        final String MorphbankMethodUri = "Morphbank.photos.search";

        // Constructs the GET URL to search.

        // `woe_id` is Yahoo! Where On Earth ID.
        // Issue
        // http://api.Morphbank.com/services/rest/?method=Morphbank.places.find&api_key=08f5318120189e9d12669465c0113351&query=australia
        // to find Australia.
        // `woe_id` here is country level code, as opposed to continent code.

        String urlToSearch = this.endpoint;

        System.out.println("Search URL: "+urlToSearch);

        /*
         * // Default parameters if not supplied. if (this.MorphbankApiKeySupplied
         * == false) { urlToSearch += "&" + "api_key=" + this.MorphbankApiKey; } if
         * (this.currentPageNumSupplied == false) { urlToSearch += "&" + "page="
         * + this.currentPageNum; } if (this.recordsPerPageSupplied == false) {
         * urlToSearch += "&" + "per_page=" + this.recordsPerPage; }
         */
        //		System.out.println(urlToSearch);
        logger.debug("URL to search is: " + "`" + urlToSearch + "`" + "\n");

        // Create an instance of HttpClient.
        HttpClient client = new HttpClient();

        // Create a method instance.
        GetMethod method = new GetMethod(urlToSearch);

        // Provide custom retry handler is necessary
        method.getParams().setParameter(HttpMethodParams.HTTP_CONTENT_CHARSET,"UTF-8");
        method.getParams().setParameter(HttpMethodParams.HTTP_ELEMENT_CHARSET,"UTF-8");
        method.getParams().setParameter(HttpMethodParams.HTTP_URI_CHARSET,"UTF-8");

        try {
            int statusCode = client.executeMethod(method);

            if (statusCode != HttpStatus.SC_OK) {
                String errMsg = "HTTP GET to " + "`" + urlToSearch + "`"
                + " returned non HTTP OK code.  Returned code "
                + statusCode + " and message " + method.getStatusLine()
                + "\n";
                method.releaseConnection();
                throw new Exception(errMsg);
            }
            
            String inputStr = method.getResponseBodyAsString();
//            inputStr = inputStr.replaceAll("[^\\x00-\\x7f]*", "");
            inputStr = inputStr.replaceAll("/dwcg:VerbatimLongitude>", "</dwcg:VerbatimLongitude>");
            inputStr = inputStr.replaceAll("/dwcg:VerbatimLatitude>", "</dwcg:VerbatimLatitude>");
            inputStr = inputStr.replaceAll("<</", "</");
            
//            Pattern p = Pattern.compile("[^<]{1}/[a-zA-Z]{1,}:[a-zA-Z]{1,}>");
//            
//            Matcher m = p.matcher(inputStr);
//            
//            int searchIdx = 0;
//            
//            while (m.find(searchIdx)) {
//                int endIdx = m.end();
//                
//                
//                
//                searchIdx = endIdx;
//            }
            
//            System.out.println(inputStr);
            InputSource is = new InputSource(new StringReader(new String(inputStr)));
            // Instantiates a DOM builder to create a DOM of the response.
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            // return a parsed Document
            return builder.parse(is);

        } catch (Exception httpErr) {
            String errMsg = "HTTP GET to `" + urlToSearch
            + "` returned HTTP error.";
            throw new Exception(errMsg, httpErr);
        } finally {
            // Release the connection.
            method.releaseConnection();
        }
    } // End of `getIndexPage` method.

    public void debugParsedDoc(ParsedDocument parsedDoc){

        System.out.println("===============================================================================");

        System.out.println("GUID: "+parsedDoc.getGuid());
        System.out.println("Content-Type: "+parsedDoc.getContentType());

        Map<String,String> dublinCore = parsedDoc.getDublinCore();
        for(String key: dublinCore.keySet()){
            System.out.println("DC: "+key+"\t"+dublinCore.get(key));
        }

        List<Triple<String,String,String>> triples = parsedDoc.getTriples(); 
        for(Triple<String,String,String> triple: triples){
            System.out.println("RDF: "+triple.getSubject()+"\t"+triple.getPredicate()+"\t"+triple.getObject());
        }

        System.out.println("===============================================================================");
    }

    /**
     * @see
     * org.ala.harvester.Harvester#setRepository(org.ala.repository.Repository)
     */
    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void setDocumentMapper(DocumentMapper documentMapper) {
        // TODO Auto-generated method stub

    }
}
