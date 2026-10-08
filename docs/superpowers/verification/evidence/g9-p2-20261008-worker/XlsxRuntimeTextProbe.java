import com.datacube.export.XlsxLayout;
import com.datacube.export.XlsxWriter;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Synthetic linked-runtime probe; raw Xstring expectations are fixed standard examples. */
public final class XlsxRuntimeTextProbe {
    private record Sample(String text, String serialized) {}
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);
        Files.createDirectory(output);
        var samples=List.of(
            new Sample("A\u0000B", "A_x0000_B"),
            new Sample("A\u0008B", "A_x0008_B"),
            new Sample("A\r\nB\tC", "A_x000D_\nB\tC"),
            new Sample("_x0000_", "_x005F_x0000_"),
            new Sample("_x005F_", "_x005F_x005F_"),
            new Sample("_x005F_x0000_", "_x005F_x005F_x005F_x0000_"),
            new Sample("_x0000__x000D_", "_x005F_x0000__x005F_x000D_"),
            new Sample("A\uFFFEB\uFFFFC", "A_xFFFE_B_xFFFF_C"),
            new Sample("中文\uD83D\uDE00<&\"", "中文\uD83D\uDE00<&\""),
            new Sample("_X0000_ _x00G0_ _x000_", "_X0000_ _x00G0_ _x000_"));
        List<String> records=new ArrayList<>();
        int roundTrips=0;
        for(boolean styled:List.of(false,true)) {
            for(int index=0;index<samples.size();index++) {
                Sample sample=samples.get(index);
                String name=(styled?"styled-":"plain-")+index+".xlsx";
                Path file=output.resolve(name);
                var columns=List.of(sample.text(),"number","boolean","null");
                com.datacube.export.RowFeed feed=sink->sink.row(Arrays.asList(sample.text(),7,true,null));
                if(styled) XlsxWriter.write(file.toFile(),columns,feed,new XlsxLayout(List.of(24,12,12,12)));
                else XlsxWriter.write(file.toFile(),columns,feed);
                Document doc=read(file);
                for(String ref:List.of("A1","A2")) {
                    Element cell=cell(doc,ref);
                    String actual=cell.getTextContent();
                    if(!cell.getAttribute("t").equals("inlineStr") || !actual.equals(sample.serialized()))
                        throw new AssertionError("Xstring mismatch: "+name+"/"+ref);
                    records.add(name+"\t"+ref+"\t"+hex(sample.text()));
                }
                if(!cell(doc,"B2").getTextContent().equals("7") || !cell(doc,"C2").getAttribute("t").equals("b")
                    || !cell(doc,"C2").getTextContent().equals("1") || find(doc,"D2")!=null)
                    throw new AssertionError("Scalar behavior changed");
                if(doc.getElementsByTagNameNS("*","f").getLength()!=0) throw new AssertionError("Unexpected formula");
                roundTrips++;
            }
        }
        int rejected=0;
        for(String invalid:List.of("A\uD800B","A\uDC00B","A\uD800\uD800B")) {
            for(boolean header:List.of(false,true)) {
                Path file=output.resolve("invalid-"+rejected+".xlsx");
                boolean failed=false;
                try {XlsxWriter.write(file.toFile(),List.of(header?invalid:"n"),sink->sink.row(List.of(header?"v":invalid)));}
                catch(Exception expected){failed=true;}
                if(!failed) throw new AssertionError("Malformed UTF16 was silently accepted");
                rejected++;
            }
        }
        Files.write(output.resolve("expected-text.tsv"),records);
        System.out.println("XLSX_RUNTIME_PACKAGES="+roundTrips+"; INVALID_UTF16_REJECTED="+rejected);
    }
    private static Document read(Path file) throws Exception {
        var factory=DocumentBuilderFactory.newDefaultInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        try(var zip=new ZipFile(file.toFile());var stream=zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))){return factory.newDocumentBuilder().parse(stream);}
    }
    private static Element find(Document doc,String ref){var cells=doc.getElementsByTagNameNS("*","c");for(int i=0;i<cells.getLength();i++){var cell=(Element)cells.item(i);if(ref.equals(cell.getAttribute("r")))return cell;}return null;}
    private static Element cell(Document doc,String ref){var result=find(doc,ref);if(result==null)throw new AssertionError("Missing "+ref);return result;}
    private static String hex(String text){StringBuilder result=new StringBuilder();for(int i=0;i<text.length();i++)result.append(String.format(Locale.ROOT,"%04X",(int)text.charAt(i)));return result.toString();}
}
