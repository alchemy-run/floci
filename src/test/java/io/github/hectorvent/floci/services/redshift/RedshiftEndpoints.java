package io.github.hectorvent.floci.services.redshift;

/**
 * JDBC URLs for a cluster's advertised endpoint. Clusters share their port (5439), as on AWS, and
 * the shared listener tells them apart by the TLS server name, so tests connect the way an AWS
 * client does: to the endpoint host name, over TLS. The self-signed proxy certificate is not
 * verified.
 */
public final class RedshiftEndpoints {

    private RedshiftEndpoints() {}

    public static String jdbcUrl(String host, int port, String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?sslmode=require&sslfactory=org.postgresql.ssl.NonValidatingFactory";
    }
}
