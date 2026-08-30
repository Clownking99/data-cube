package com.datacube.export;

import java.nio.file.Path;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import org.w3c.dom.Document;

final class XlsxTestDocuments {
    private XlsxTestDocuments() {}
    static Document read(Path path, String entry) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var zip = new ZipFile(path.toFile())) {
            var part = zip.getEntry(entry);
            if (part == null) throw new AssertionError("Missing XLSX part: " + entry);
            try (var input = zip.getInputStream(part)) {
                return factory.newDocumentBuilder().parse(input);
            }
        }
    }
    static String value(Document document, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, document);
    }
    static int count(Document document, String expression) throws Exception {
        return ((Double) XPathFactory.newInstance().newXPath().evaluate(
                "count(" + expression + ")", document, XPathConstants.NUMBER)).intValue();
    }
}
