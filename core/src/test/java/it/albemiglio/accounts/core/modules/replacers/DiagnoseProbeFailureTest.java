package it.albemiglio.accounts.core.modules.replacers;

import it.albemiglio.accounts.core.modules.Diagnosis;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The probe tries every encoding to catch a wrong {@code format}. On MySQL, binding raw bytes against a
 * utf8 text column is an error rather than a miss ("Invalid utf8mb4 character string"), and that must not
 * bury the encoding the template actually configured — CMI's dashed player_uuid was reported MISSING for
 * exactly this reason.
 */
class DiagnoseProbeFailureTest {

    private static final UUID PROBE = new UUID(0x069a79f444e94726L, 0xa5befca90e38aaf5L);

    /** A connection whose statements refuse setBytes, the way MySQL refuses bytes on a text column. */
    private static Connection rejectingBinaryBinds(Connection real) {
        InvocationHandler onConnection = (proxy, method, args) -> {
            Object result = method.invoke(real, args);
            if (!(result instanceof PreparedStatement)) {
                return result;
            }
            PreparedStatement ps = (PreparedStatement) result;
            return Proxy.newProxyInstance(DiagnoseProbeFailureTest.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p2, m2, a2) -> {
                        if ("setBytes".equals(m2.getName())) {
                            throw new SQLException("Invalid utf8mb4 character string: '069A79'");
                        }
                        return m2.invoke(ps, a2);
                    });
        };
        return (Connection) Proxy.newProxyInstance(DiagnoseProbeFailureTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, onConnection);
    }

    @Test
    void aRejectedBinaryProbeDoesNotHideTheConfiguredMatch() throws Exception {
        try (Connection real = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = real.createStatement()) {
                st.execute("CREATE TABLE users (player_uuid TEXT)");
                st.execute("INSERT INTO users VALUES ('" + PROBE + "')");
            }
            List<Diagnosis> out = new ColumnReplacer("users", "player_uuid", UuidCodec.DASHED)
                    .diagnose(rejectingBinaryBinds(real), PROBE, "cmi");

            assertEquals(1, out.size());
            assertEquals(Diagnosis.Status.VERIFIED, out.get(0).getStatus());
            assertEquals(1, out.get(0).getOccurrences());
        }
    }
}
