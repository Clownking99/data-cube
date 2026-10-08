import java.nio.file.*;
import java.security.*;
import java.util.*;
public class NulEvidenceAlias {
 public static void main(String[] args)throws Exception{
  Path directory=Path.of(args[0]), original=directory.resolve("nul.xlsx"), alias=directory.resolve("case-nul.xlsx");
  byte[] originalBytes=Files.readAllBytes(original); Files.copy(original,alias);
  byte[] aliasBytes=Files.readAllBytes(alias);
  if(!Arrays.equals(originalBytes,aliasBytes))throw new AssertionError("Alias bytes differ");
  String sha=HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(originalBytes));
  Files.writeString(directory.resolve("audit-aliases.json"),"[{\"original\":\"nul.xlsx\",\"auditFile\":\"case-nul.xlsx\",\"length\":"+originalBytes.length+",\"sha256\":\""+sha+"\"}]\n");
  System.out.println("ALIAS_BYTE_EXACT=true length="+originalBytes.length+" sha256="+sha);
 }
}
