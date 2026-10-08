package com.datacube.update;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;
/** Actual aliases/reparse directories confined to retained owned roots. */
@EnabledOnOs(OS.WINDOWS)
class UpdatePathsTest {
 @Test void shortNameAliasAcceptsImageAndMissingStagingWithoutRewritingRequestedIdentity() throws Exception {
  Path root=owned(), image=UpdateTestSupport.image(root.resolve("application-image"),(byte)42), alias=shortPath(root);
  assertNotEquals(root.toRealPath(),alias,"regression requires real 8.3 alias");
  assertEquals(alias.resolve("application-image"),UpdatePaths.image(alias.resolve("application-image")));
  Path missing=alias.resolve("not-created/staging");
  assertEquals(missing,UpdatePaths.noLinks(missing));
  assertArrayEquals(new byte[]{42},Files.readAllBytes(image.resolve("DataCube.exe")));
  assertFalse(Files.exists(root.resolve("not-created")));
 }
 @Test void junctionAtTargetOrAncestorRejectsBeforeExtractionWithoutTouchingTarget() throws Exception {
  Path root=owned(),target=Files.createDirectory(root.resolve("target"));
  Path sentinel=Files.writeString(target.resolve("keep.txt"),"owned sentinel"),junction=root.resolve("junction");
  ProcessBuilder command=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",
    "New-Item -ItemType Junction -Path $env:DATACUBE_LINK -Target $env:DATACUBE_TARGET | Out-Null");
  command.environment().put("DATACUBE_LINK",junction.toString());command.environment().put("DATACUBE_TARGET",target.toString());run(command);
  var attrs=Files.readAttributes(junction,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
  assertTrue(attrs.isOther(),"actual NTFS reparse flag visible without following");
  assertThrows(IOException.class,()->UpdatePaths.noLinks(junction));
  assertThrows(IOException.class,()->UpdatePaths.canonicalExisting(junction));
  assertThrows(IOException.class,()->UpdatePaths.noLinks(junction.resolve("missing/staging")));
  Path archive=Files.write(root.resolve("image.zip"),UpdateTestSupport.zip(UpdateTestSupport.IMAGE));
  try(var control=UpdateTestSupport.control()){assertThrows(IOException.class,()->PortableArchive.extract(archive,junction.resolve("staging"),control));}
  assertEquals("owned sentinel",Files.readString(sentinel));assertFalse(Files.exists(target.resolve("staging")));
 }
 @Test void aliasHandoffPinsCanonicalTargetAndBothWorkspacesWithExactStartupIdentity() throws Exception {
  Path root=owned(),alias=shortPath(root);
  assertNotEquals(root,alias,"regression requires real 8.3 alias");
  for(InstallMode mode: new InstallMode[]{InstallMode.PORTABLE,InstallMode.INSTALLED}) {
   Path app=UpdateTestSupport.image(root.resolve(mode.name()),(byte)42);
   Path requested=alias.resolve(mode.name());
   var fixture=new UpdateTestSupport();
   try(var control=UpdateTestSupport.control()) {
    var ready=fixture.applier().prepare(UpdateTestSupport.release(),"3.0.0",mode,requested,control,null);
    assertEquals(app.toRealPath(LinkOption.NOFOLLOW_LINKS),ready.appDir);
    assertEquals(ready.workspace.toRealPath(LinkOption.NOFOLLOW_LINKS),ready.workspace);
    var plan=(java.util.Map<?,?>)MiniJson.parse(Files.readString(ready.plan));
    assertEquals(ready.appDir.toString(),plan.get("appDir"));
    assertEquals(ready.workspace.toString(),plan.get("workspace"));
    assertEquals(ready.asset.toString(),plan.get("asset"));
    if(mode==InstallMode.PORTABLE) {
     String argument=UpdateStartupTest.argument(ready.plan);
     Path other=UpdateTestSupport.image(root.resolve("unrelated"),(byte)43);
     assertFalse(UpdateStartup.acknowledge(java.util.List.of(argument),UpdateTestSupport.VERSION,other));
     assertTrue(UpdateStartup.acknowledge(java.util.List.of(argument),UpdateTestSupport.VERSION,requested));
     assertFalse(UpdateStartup.acknowledge(java.util.List.of(argument),UpdateTestSupport.VERSION,requested));
    }
   }
  }
 }
 private static Path owned() throws IOException {return Files.createTempDirectory("datacube-update-paths-"+UUID.randomUUID()).toRealPath();}
 private static Path shortPath(Path root) throws Exception {
  var command=new ProcessBuilder("cmd.exe","/d","/c","for %I in (\"%DATACUBE_OWNED_PATH%\") do @echo %~sI");
  command.environment().put("DATACUBE_OWNED_PATH",root.toString());return Path.of(run(command).trim());
 }
 private static String run(ProcessBuilder command) throws Exception {
  command.redirectErrorStream(true);Process process=command.start();
  assertTrue(process.waitFor(10,TimeUnit.SECONDS),"owned path helper must terminate");
  String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),output);return output;
 }
}
