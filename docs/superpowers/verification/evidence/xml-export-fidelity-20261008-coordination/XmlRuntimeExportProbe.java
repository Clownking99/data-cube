import com.datacube.export.ResultExporter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Runs solely on synthetic in-memory values in the linked product runtime. */
public class XmlRuntimeExportProbe {
    public static void main(String[] args) throws Exception {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var parser = factory.newDocumentBuilder();
        String value = "SYNTHETIC\r\n\uD83D\uDE00\t<&";
        int checked = 0;
        for (String label : List.of("C", "中文", "\u00AA", "\u037F", "\u08A0", "a\t\r\nb", "\uD83D\uDE00")) {
            var output = new StringWriter();
            ResultExporter.writeXml(output, List.of(label), List.of(List.of(value)));
            var document = parser.parse(new InputSource(new StringReader(output.toString())));
            Element cell = (Element) document.getElementsByTagName("ROW").item(0)
                    .getChildNodes().item(1);
            String original = cell.hasAttribute("name") ? cell.getAttribute("name") : cell.getTagName();
            if (!label.equals(original) || !value.equals(cell.getTextContent()))
                throw new AssertionError("Linked XML round trip failed");
            checked++;
        }
        try {
            ResultExporter.writeXml(new StringWriter(), List.of("C"), List.of(List.of("A\u0000B")));
            throw new AssertionError("Linked XML character gate failed");
        } catch (ResultExporter.InvalidXmlCharacterException expected) {
            if (!"XML contains an unrepresentable character".equals(expected.getMessage()))
                throw new AssertionError("Unsafe linked XML error");
        }
        System.out.println("XML_RUNTIME_ROUND_TRIPS=" + checked + "; INVALID_CHARACTER_REJECTED=true");
    }
}
