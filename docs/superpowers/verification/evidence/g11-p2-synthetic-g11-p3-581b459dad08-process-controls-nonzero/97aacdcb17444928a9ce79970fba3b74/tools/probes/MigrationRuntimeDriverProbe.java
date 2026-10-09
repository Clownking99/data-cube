/** Runtime-only discovery: never calls connect/open or loads user configuration. */
public class MigrationRuntimeDriverProbe {
    public static void main(String[] args) throws Exception {
        var factory=Class.forName("com.datacube.migration.MigrationConnections").getMethod("driverFor",String.class);
        System.out.println("oracle="+factory.invoke(null,"jdbc:oracle:thin:@example.invalid:1/synthetic").getClass().getName());
        System.out.println("postgres="+factory.invoke(null,"jdbc:postgresql://example.invalid:1/synthetic").getClass().getName());
        System.out.println("connectCalls=0; no credentials or user profile loaded");
    }
}
