import com.datacube.export.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.*;
import org.xml.sax.*;
public class XmlExportDiagnosis {
 static String hex(String text){return text.codePoints().mapToObj(c->String.format("%04X",c)).reduce((a,b)->a+","+b).orElse("");}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);
  String[][] cases={{"nul","C","a\u0000b"},{"fffe","C","a\uFFFEb"},{"ffff","C","a\uFFFFb"},{"surrogate","C","a\uD800b"},{"cr","C","a\rb"},{"attribute","a\t\r\nb","v"},{"name-letter","\u00AA","v"},{"supplementary","C","a\uD83D\uDE00b"}};
  for(String[] c:cases){
   Path target=root.resolve(c[0]+".xml"); Files.writeString(target,"OLD");
   ResultExportOperation op=new ResultExportOperation();
   try{
    new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(target),op,(tmp,token)->{
     try(var w=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8)){ResultExporter.writeXml(w,List.of(c[1]),List.of(List.of((Object)c[2])));}
    });
    DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","");
    f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema","");
    var b=f.newDocumentBuilder();b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){public void fatalError(SAXParseException e)throws SAXException{throw e;}});
    try{
     var doc=b.parse(target.toFile());var cell=(org.w3c.dom.Element)doc.getElementsByTagName("ROW").item(0).getFirstChild();
    }catch(ClassCastException ignore){}
    try{
     var doc=b.parse(target.toFile());var nodes=doc.getElementsByTagName("ROW").item(0).getChildNodes();org.w3c.dom.Element cell=null;
     for(int i=0;i<nodes.getLength();i++)if(nodes.item(i) instanceof org.w3c.dom.Element e){cell=e;break;}
     String name=cell.hasAttribute("name")?cell.getAttribute("name"):cell.getTagName();
     System.out.println(c[0]+" published="+op.published()+" parse=true valueExact="+c[2].equals(cell.getTextContent())+" columnExact="+c[1].equals(name)+" actualValueHex="+hex(cell.getTextContent())+" actualColumnHex="+hex(name));
    }catch(SAXParseException failure){System.out.println(c[0]+" published="+op.published()+" parse=false error="+failure.getMessage());}
   }catch(Exception failure){System.out.println(c[0]+" published="+op.published()+" stage="+(failure instanceof SafeResultFilePublisher.Failure x?x.stage():failure.getClass().getSimpleName())+" oldPreserved="+"OLD".equals(Files.readString(target)));}
  }
 }
}
