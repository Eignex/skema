import com.eignex.skema.JsonSpec;
import com.eignex.skema.JsonSpecKt;
import com.eignex.skema.SchemaDef;
import java.util.Map;
import kotlinx.serialization.json.Json;

public final class Consumer {
    public static void main(String[] args) {
        var spec = new JsonSpec.Int(0, 100, null, null, null);
        var definition = new SchemaDef<JsonSpec>(Map.of("count", spec));
        var schema = JsonSpecKt.toJsonSchema(definition, Map.of());
        var value = new JsonSpec.Ref("#/$defs/Count");
        var serializer = JsonSpec.Ref.Companion.serializer();
        var encoded = Json.Default.encodeToString(serializer, value);
        if (!schema.toString().contains("count")
                || !value.equals(Json.Default.decodeFromString(serializer, encoded))) {
            throw new AssertionError("Published schema/serialization failed");
        }
        System.out.println("Published Java consumer passed");
    }
}
