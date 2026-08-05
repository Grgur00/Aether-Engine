package io.aetherdb.codec.processor;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/** Generates deterministic AER1 codecs and registration metadata for annotated records. */
@SupportedAnnotationTypes("*")
@SupportedOptions({"aether.schemaMode", "aether.schemaDirectory"})
public final class AetherRecordProcessor extends AbstractProcessor {
    private final List<GeneratedRecord> generated = new ArrayList<>();
    private boolean resourcesWritten;

    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_21; }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(AetherRecord.class)) {
            if (element instanceof TypeElement type && validateAndGenerate(type)) {
                // Registration resources are emitted after all source rounds finish.
            }
        }
        if (roundEnvironment.processingOver() && !resourcesWritten && !generated.isEmpty()) {
            resourcesWritten = true;
            writeRegistrationResources();
        }
        return true;
    }

    private boolean validateAndGenerate(TypeElement type) {
        Messager messages = processingEnv.getMessager();
        if (type.getKind() != ElementKind.RECORD) {
            messages.printMessage(Diagnostic.Kind.ERROR, "@AetherRecord requires a Java record", type);
            return false;
        }
        if (!type.getModifiers().contains(Modifier.PUBLIC)) {
            messages.printMessage(Diagnostic.Kind.ERROR, "@AetherRecord must be public", type);
            return false;
        }
        AetherRecord annotation = type.getAnnotation(AetherRecord.class);
        LockedSchema locked = loadLock(type);
        UUID schemaId;
        try {
            String declaredSchemaId = annotation.schemaId();
            if (declaredSchemaId.isEmpty()) {
                if (locked == null) {
                    messages.printMessage(Diagnostic.Kind.ERROR,
                            "AETHER_SCHEMA_LOCK_MISSING for " + type.getQualifiedName()
                                    + ". Run: ./gradlew aetherSchemaInit", type);
                    return false;
                }
                declaredSchemaId = locked.schemaId.toString();
            }
            schemaId = UUID.fromString(declaredSchemaId);
            if (schemaId.equals(new UUID(0, 0))) throw new IllegalArgumentException("zero UUID");
        }
        catch (IllegalArgumentException failure) {
            messages.printMessage(Diagnostic.Kind.ERROR, "SCHEMA_ID_INVALID", type);
            return false;
        }
        if (annotation.version() < 1) {
            messages.printMessage(Diagnostic.Kind.ERROR, "SCHEMA_VERSION_INVALID", type);
            return false;
        }
        if (locked != null) {
            if (!locked.schemaId.equals(schemaId)) {
                messages.printMessage(Diagnostic.Kind.ERROR, "AETHER_SCHEMA_ID_LOCK_MISMATCH", type);
                return false;
            }
            if (locked.version != annotation.version()) {
                messages.printMessage(Diagnostic.Kind.ERROR,
                        "AETHER_SCHEMA_UPDATE_REQUIRED: lock version " + locked.version
                                + ", source version " + annotation.version()
                                + ". Run: ./gradlew aetherSchemaUpdate", type);
                return false;
            }
        }

        List<FieldModel> fields = new ArrayList<>();
        Set<Integer> fieldIds = new HashSet<>();
        boolean valid = true;
        for (RecordComponentElement component : type.getRecordComponents()) {
            AetherField field = component.getAnnotation(AetherField.class);
            int fieldId = field == null ? 0 : field.id();
            LockedField lockedField = locked == null ? null : locked.fields.get(
                    component.getSimpleName().toString());
            if (fieldId == 0) {
                if (lockedField == null) {
                    messages.printMessage(Diagnostic.Kind.ERROR,
                            "AETHER_SCHEMA_LOCK_MISSING field identity for "
                                    + component.getSimpleName()
                                    + ". Run: ./gradlew aetherSchemaUpdate", component);
                    valid = false;
                    continue;
                }
                fieldId = lockedField.id;
            }
            else if (lockedField != null && lockedField.id != fieldId) {
                messages.printMessage(Diagnostic.Kind.ERROR, "AETHER_FIELD_ID_LOCK_MISMATCH", component);
                valid = false;
            }
            if (fieldId < 16 || fieldId > 536_870_911) {
                messages.printMessage(Diagnostic.Kind.ERROR, "FIELD_ID_RESERVED", component);
                valid = false;
            }
            if (!fieldIds.add(fieldId)) {
                messages.printMessage(Diagnostic.Kind.ERROR, "FIELD_ID_DUPLICATE", component);
                valid = false;
            }
            FieldType fieldType = resolveType(component.asType());
            if (fieldType == null) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR,
                        "FIELD_TYPE_UNSUPPORTED: " + component.asType(),
                        component);
                valid = false;
                continue;
            }
            int bound = fieldType.fixedBound;
            if (fieldType == FieldType.STRING) {
                AetherMaxLength maximum = component.getAnnotation(AetherMaxLength.class);
                if (maximum != null && maximum.value() > 0) bound = maximum.value();
                else if (lockedField != null && lockedField.bound > 0) bound = lockedField.bound;
                else {
                    messages.printMessage(Diagnostic.Kind.ERROR, "FIELD_BOUND_REQUIRED", component);
                    valid = false;
                    continue;
                }
            }
            if (lockedField != null
                    && (!lockedField.javaType.equals(component.asType().toString())
                            || lockedField.bound != bound)) {
                messages.printMessage(Diagnostic.Kind.ERROR,
                        "AETHER_SCHEMA_UPDATE_REQUIRED for field " + component.getSimpleName(), component);
                valid = false;
            }
            fields.add(new FieldModel(
                    fieldId, component.getSimpleName().toString(),
                    component.asType().toString(), fieldType, bound));
        }
        if (!valid) return false;

        fields.sort(Comparator.comparingInt(FieldModel::id));
        String qualifiedName = type.getQualifiedName().toString();
        String packageName = processingEnv.getElementUtils().getPackageOf(type)
                .getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String descriptor = descriptor(schemaId, annotation.version(), qualifiedName, fields);
        byte[] fingerprint = sha256(descriptor.getBytes(StandardCharsets.UTF_8));
        if (locked != null && !locked.descriptorSha256.isEmpty()
                && !locked.descriptorSha256.equals(hex(fingerprint))) {
            messages.printMessage(Diagnostic.Kind.ERROR,
                    "AETHER_SCHEMA_UPDATE_REQUIRED: descriptor fingerprint differs. Run: ./gradlew aetherSchemaUpdate",
                    type);
            return false;
        }
        int maximumBytes = maximumBytes(fields);
        try {
            writeCodec(type, packageName, simpleName, schemaId, annotation.version(),
                    fields, fingerprint, maximumBytes);
            writeProvider(packageName, simpleName);
            writeDescriptor(schemaId, annotation.version(), descriptor, type);
            generated.add(new GeneratedRecord(
                    qualifiedName,
                    packageName + "." + simpleName + "_AetherCodecProvider",
                    schemaId,
                    annotation.version(),
                    hex(fingerprint)));
            return true;
        }
        catch (IOException failure) {
            messages.printMessage(
                    Diagnostic.Kind.ERROR, "CODEC_GENERATION_FAILED: " + failure.getMessage(), type);
            return false;
        }
    }

    private LockedSchema loadLock(TypeElement type) {
        String directory = processingEnv.getOptions().get("aether.schemaDirectory");
        if (directory == null || directory.isBlank()) return null;
        Path index = Path.of(directory).resolve("index.json");
        if (!Files.isRegularFile(index)) return null;
        try {
            String indexJson = Files.readString(index, StandardCharsets.UTF_8);
            String javaType = type.getQualifiedName().toString();
            java.util.regex.Matcher entry = java.util.regex.Pattern.compile(
                    "\\{\\s*\\\"javaType\\\"\\s*:\\s*\\\""
                            + java.util.regex.Pattern.quote(javaType)
                            + "\\\"\\s*,\\s*\\\"schemaId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
                            + "\\s*,\\s*\\\"currentVersion\\\"\\s*:\\s*(\\d+)"
                            + "\\s*,\\s*\\\"lockFile\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                    .matcher(indexJson);
            if (!entry.find()) return null;
            UUID schemaId = UUID.fromString(entry.group(1));
            int version = Integer.parseInt(entry.group(2));
            Path lockPath = Path.of(directory).resolve(entry.group(3)).normalize();
            if (!lockPath.startsWith(Path.of(directory).normalize()) || !Files.isRegularFile(lockPath)) {
                throw new IllegalArgumentException("invalid lock path");
            }
            String lockJson = Files.readString(lockPath, StandardCharsets.UTF_8);
            java.util.regex.Matcher field = java.util.regex.Pattern.compile(
                    "\\{\\s*\\\"id\\\"\\s*:\\s*(\\d+)\\s*,"
                            + "\\s*\\\"javaName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,"
                            + "\\s*\\\"wireType\\\"\\s*:\\s*\\\"[^\\\"]+\\\"\\s*,"
                            + "\\s*\\\"type\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,"
                            + "\\s*\\\"requiredness\\\"\\s*:\\s*\\\"[^\\\"]+\\\"\\s*,"
                            + "\\s*\\\"maximumEncodedBytes\\\"\\s*:\\s*(\\d+)\\s*\\}")
                    .matcher(lockJson);
            java.util.Map<String, LockedField> fields = new java.util.HashMap<>();
            while (field.find()) {
                fields.put(field.group(2), new LockedField(
                        Integer.parseInt(field.group(1)), field.group(3),
                        Integer.parseInt(field.group(4))));
            }
            java.util.regex.Matcher fingerprint = java.util.regex.Pattern.compile(
                    "\\\"descriptorSha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"")
                    .matcher(lockJson);
            String descriptorSha256 = fingerprint.find() ? fingerprint.group(1) : "";
            return new LockedSchema(
                    schemaId, version, java.util.Map.copyOf(fields), descriptorSha256);
        }
        catch (IOException | IllegalArgumentException failure) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "AETHER_SCHEMA_LOCK_INVALID for " + type.getQualifiedName()
                            + ": " + failure.getMessage(), type);
            return null;
        }
    }

    private void writeCodec(
            TypeElement type,
            String packageName,
            String simpleName,
            UUID schemaId,
            int version,
            List<FieldModel> sortedFields,
            byte[] fingerprint,
            int maximumBytes) throws IOException {
        String codecName = simpleName + "_AetherCodec";
        JavaFileObject source = processingEnv.getFiler().createSourceFile(
                packageName + "." + codecName, type);
        List<? extends RecordComponentElement> constructorFields = type.getRecordComponents();
        try (Writer output = source.openWriter()) {
            output.write("package " + packageName + ";\n\n");
            output.write("@javax.annotation.processing.Generated(\""
                    + AetherRecordProcessor.class.getName() + "\")\n");
            output.write("public final class " + codecName
                    + " implements io.aetherdb.api.typed.ValueCodec<" + simpleName + "> {\n");
            output.write("  public static final " + codecName + " INSTANCE = new "
                    + codecName + "();\n");
            output.write("  private static final java.util.UUID SCHEMA_ID = java.util.UUID.fromString(\""
                    + schemaId + "\");\n");
            output.write("  private static final byte[] FINGERPRINT = new byte[] {"
                    + byteLiterals(fingerprint) + "};\n");
            output.write("  private static final int MAXIMUM_BYTES = " + maximumBytes + ";\n");
            output.write("  private " + codecName + "() {}\n");
            output.write("  @Override public java.util.UUID schemaId() { return SCHEMA_ID; }\n");
            output.write("  @Override public int currentSchemaVersion() { return " + version + "; }\n");
            output.write("  @Override public int maximumEncodedSize(" + simpleName
                    + " value) { return MAXIMUM_BYTES; }\n");
            output.write("  @Override public byte[] fingerprint() { return java.util.Arrays.copyOf(FINGERPRINT, FINGERPRINT.length); }\n");
            output.write("  @Override public byte[] encode(" + simpleName + " value) {\n");
            output.write("    java.util.Objects.requireNonNull(value, \"value\");\n");
            output.write("    var writer = new io.aetherdb.codec.generated.CanonicalRecordWriter(MAXIMUM_BYTES);\n");
            for (FieldModel field : sortedFields) output.write(encodeStatement(field));
            output.write("    return writer.finish();\n  }\n");
            output.write("  @Override public " + simpleName
                    + " decode(int schemaVersion, byte[] encoded) {\n");
            output.write("    if (schemaVersion != " + version
                    + ") throw new IllegalArgumentException(\"SCHEMA_VERSION_UNSUPPORTED: \" + schemaVersion);\n");
            for (RecordComponentElement component : constructorFields) {
                FieldModel field = findByName(sortedFields, component.getSimpleName().toString());
                output.write("    " + field.javaType + " " + field.name + " = "
                        + defaultValue(field.type) + ";\n");
                output.write("    boolean seen_" + field.name + " = false;\n");
            }
            output.write("    var reader = new io.aetherdb.codec.generated.CanonicalRecordReader(encoded, MAXIMUM_BYTES);\n");
            output.write("    while (reader.next()) {\n      switch (reader.fieldId()) {\n");
            for (FieldModel field : sortedFields) output.write(decodeCase(field));
            output.write("        default -> { /* validated unknown field skipped */ }\n");
            output.write("      }\n    }\n");
            for (RecordComponentElement component : constructorFields) {
                String name = component.getSimpleName().toString();
                output.write("    if (!seen_" + name
                        + ") throw new IllegalArgumentException(\"missing required field: "
                        + name + "\");\n");
            }
            output.write("    return new " + simpleName + "(");
            for (int index = 0; index < constructorFields.size(); index++) {
                if (index > 0) output.write(", ");
                output.write(constructorFields.get(index).getSimpleName().toString());
            }
            output.write(");\n  }\n}\n");
        }
    }

    private void writeProvider(String packageName, String simpleName) throws IOException {
        String providerName = simpleName + "_AetherCodecProvider";
        JavaFileObject source = processingEnv.getFiler().createSourceFile(
                packageName + "." + providerName);
        try (Writer output = source.openWriter()) {
            output.write("package " + packageName + ";\n\n");
            output.write("@javax.annotation.processing.Generated(\""
                    + AetherRecordProcessor.class.getName() + "\")\n");
            output.write("public final class " + providerName
                    + " implements io.aetherdb.codec.generated.GeneratedCodecProvider {\n");
            output.write("  @Override public Class<?> recordType() { return "
                    + simpleName + ".class; }\n");
            output.write("  @Override public io.aetherdb.api.typed.ValueCodec<?> codec() { return "
                    + simpleName + "_AetherCodec.INSTANCE; }\n}\n");
        }
    }

    private void writeDescriptor(
            UUID schemaId, int version, String descriptor, TypeElement originating) throws IOException {
        String resource = "META-INF/aether/schemas/" + schemaId + "/" + version + ".aesch";
        try (Writer output = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", resource, originating)
                .openWriter()) {
            output.write(descriptor);
        }
    }

    private void writeRegistrationResources() {
        generated.sort(Comparator.comparing(GeneratedRecord::qualifiedName));
        Filer filer = processingEnv.getFiler();
        try (Writer services = filer.createResource(
                        StandardLocation.CLASS_OUTPUT,
                        "",
                        "META-INF/services/io.aetherdb.codec.generated.GeneratedCodecProvider")
                .openWriter();
                Writer index = filer.createResource(
                                StandardLocation.CLASS_OUTPUT,
                                "",
                                "META-INF/aether/generated-codecs.idx")
                        .openWriter()) {
            for (GeneratedRecord record : generated) {
                services.write(record.providerName + "\n");
                index.write(record.qualifiedName + "|" + record.schemaId + "|" + record.version
                        + "|" + record.providerName + "|" + record.fingerprint + "\n");
            }
        }
        catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "CODEC_GENERATION_FAILED registration: " + failure.getMessage());
        }
    }

    private static String encodeStatement(FieldModel field) {
        return "    writer.field(" + field.id + ", " + field.type.wireConstant + ", "
                + field.type.writerExpression(field.name, field.bound) + ");\n";
    }

    private static String decodeCase(FieldModel field) {
        return "        case " + field.id + " -> { reader.requireWireType("
                + field.type.wireConstant + "); " + field.name + " = "
                + field.type.readerExpression(field.bound) + "; seen_" + field.name
                + " = true; }\n";
    }

    private static FieldModel findByName(List<FieldModel> fields, String name) {
        return fields.stream().filter(field -> field.name.equals(name)).findFirst().orElseThrow();
    }

    private static String defaultValue(FieldType type) {
        return switch (type) {
            case LONG -> "0L";
            case INT -> "0";
            case BOOLEAN -> "false";
            case DOUBLE -> "0.0d";
            default -> "null";
        };
    }

    private static FieldType resolveType(TypeMirror type) {
        if (type.getKind() == TypeKind.LONG) return FieldType.LONG;
        if (type.getKind() == TypeKind.INT) return FieldType.INT;
        if (type.getKind() == TypeKind.BOOLEAN) return FieldType.BOOLEAN;
        if (type.getKind() == TypeKind.DOUBLE) return FieldType.DOUBLE;
        return switch (type.toString()) {
            case "java.lang.String" -> FieldType.STRING;
            case "java.util.UUID" -> FieldType.UUID;
            case "java.time.Instant" -> FieldType.INSTANT;
            default -> null;
        };
    }

    private static String descriptor(
            UUID schemaId, int version, String type, List<FieldModel> fields) {
        StringBuilder descriptor = new StringBuilder("AETHER_SCHEMA_DESCRIPTOR_V1\n")
                .append("schemaId=").append(schemaId).append('\n')
                .append("version=").append(version).append('\n')
                .append("type=").append(type).append('\n')
                .append("payloadFormat=1\nunknownFields=SKIP\n");
        for (FieldModel field : fields) {
            descriptor.append("field=").append(field.id).append('|').append(field.name)
                    .append('|').append(field.type).append('|').append(field.bound).append('\n');
        }
        return descriptor.toString();
    }

    private static int maximumBytes(List<FieldModel> fields) {
        int maximum = 24;
        for (FieldModel field : fields) maximum = Math.addExact(maximum, 22 + field.bound);
        return maximum;
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static String byteLiterals(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < bytes.length; index++) {
            if (index > 0) result.append(',');
            result.append("(byte)0x").append(String.format("%02x", bytes[index] & 0xff));
        }
        return result.toString();
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private enum FieldType {
        BOOLEAN(1, "io.aetherdb.codec.generated.WireType.BOOL"),
        LONG(10, "io.aetherdb.codec.generated.WireType.SIGNED_VARINT"),
        INT(5, "io.aetherdb.codec.generated.WireType.SIGNED_VARINT"),
        DOUBLE(8, "io.aetherdb.codec.generated.WireType.FIXED64"),
        STRING(-1, "io.aetherdb.codec.generated.WireType.STRING_UTF8"),
        UUID(16, "io.aetherdb.codec.generated.WireType.UUID128"),
        INSTANT(20, "io.aetherdb.codec.generated.WireType.TEMPORAL");

        private final int fixedBound;
        private final String wireConstant;

        FieldType(int fixedBound, String wireConstant) {
            this.fixedBound = fixedBound;
            this.wireConstant = wireConstant;
        }

        String writerExpression(String name, int bound) {
            return switch (this) {
                case BOOLEAN -> "io.aetherdb.codec.generated.CanonicalRecordWriter.bool(value."
                        + name + "())";
                case LONG, INT -> "io.aetherdb.codec.generated.CanonicalRecordWriter.signedLong(value."
                        + name + "())";
                case DOUBLE -> "io.aetherdb.codec.generated.CanonicalRecordWriter.fixed64(value."
                        + name + "())";
                case STRING -> "io.aetherdb.codec.generated.CanonicalRecordWriter.string(value."
                        + name + "(), " + bound + ")";
                case UUID -> "io.aetherdb.codec.generated.CanonicalRecordWriter.uuid(value."
                        + name + "())";
                case INSTANT -> "io.aetherdb.codec.generated.CanonicalRecordWriter.instant(value."
                        + name + "())";
            };
        }

        String readerExpression(int bound) {
            return switch (this) {
                case BOOLEAN -> "reader.boolValue()";
                case LONG -> "reader.signedLongValue()";
                case INT -> "java.lang.Math.toIntExact(reader.signedLongValue())";
                case DOUBLE -> "reader.fixed64Value()";
                case STRING -> "reader.stringValue(" + bound + ")";
                case UUID -> "reader.uuidValue()";
                case INSTANT -> "reader.instantValue()";
            };
        }
    }

    private record FieldModel(
            int id, String name, String javaType, FieldType type, int bound) {}

    private record GeneratedRecord(
            String qualifiedName,
            String providerName,
            UUID schemaId,
            int version,
            String fingerprint) {}

    private record LockedSchema(
            UUID schemaId,
            int version,
            java.util.Map<String, LockedField> fields,
            String descriptorSha256) {}
    private record LockedField(int id, String javaType, int bound) {}
}
