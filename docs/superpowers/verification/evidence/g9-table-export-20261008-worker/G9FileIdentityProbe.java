import java.nio.file.*;
import java.nio.file.attribute.*;
public class G9FileIdentityProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("g9-identity-probe-");
        Path file = Files.writeString(root.resolve("synthetic"), "synthetic");
        for (Path current = file; current != null; current = current.getParent()) {
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            System.out.println(current + " regular=" + attributes.isRegularFile() + " directory=" + attributes.isDirectory()
                    + " other=" + attributes.isOther() + " symlink=" + attributes.isSymbolicLink() + " key=" + attributes.fileKey());
        }
        Path alias = Files.createLink(root.resolve("alias"), file);
        System.out.println("owned hardlink isSameFile=" + Files.isSameFile(file, alias));
    }
}
