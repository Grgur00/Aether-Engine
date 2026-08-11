package io.aetherdb.codec.processor;

import io.aetherdb.codec.annotation.AetherEnum;
import io.aetherdb.codec.annotation.AetherEnumValue;
import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxEntries;
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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/** Generates deterministic AER1 codecs and registration metadata for annotated records. */
@SupportedAnnotationTypes("*")
@SupportedOptions({"aether.schemaMode", "aether.schemaDirectory", "aether.schemaProposalDirectory"})
public final class AetherRecordProcessor extends AbstractProcessor {
    /** Creates an annotation processor instance for the Java compiler. */
    public AetherRecordProcessor() {}

    private final List<GeneratedRecord> generated = new ArrayList<>();
    private final List<ProposedSchema> proposals = new ArrayList<>();
    private boolean resourcesWritten;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_21;
    }

    @Override
    public boolean process(
            Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(AetherRecord.class)) {
            if (element instanceof TypeElement type && validateAndGenerate(type)) {
                // Registration resources are emitted after all source rounds finish.
            }
        }
        if (roundEnvironment.processingOver() && !resourcesWritten && !generated.isEmpty()) {
            resourcesWritten = true;
            writeRegistrationResources();
            writeSchemaProposals();
        }
        return true;
    }

    private boolean validateAndGenerate(TypeElement type) {
        Messager messages = processingEnv.getMessager();
        if (type.getKind() != ElementKind.RECORD) {
            messages.printMessage(
                    Diagnostic.Kind.ERROR, "@AetherRecord requires a Java record", type);
            return false;
        }
        if (!type.getModifiers().contains(Modifier.PUBLIC)) {
            messages.printMessage(Diagnostic.Kind.ERROR, "@AetherRecord must be public", type);
            return false;
        }
        AetherRecord annotation = type.getAnnotation(AetherRecord.class);
        LockedSchema locked = loadLock(type);
        boolean proposing = isProposalMode();
        UUID schemaId;
        try {
            String declaredSchemaId = annotation.schemaId();
            if (declaredSchemaId.isEmpty()) {
                if (locked == null) {
                    if (!proposing) {
                        messages.printMessage(
                                Diagnostic.Kind.ERROR,
                                "AETHER_SCHEMA_LOCK_MISSING: No committed Aether schema lock exists"
                                        + " for "
                                        + type.getQualifiedName()
                                        + ".\n\nRun:\n"
                                        + "  ./gradlew aetherSchemaInit\n"
                                        + "  ./gradlew aetherSchemaAccept",
                                type);
                        return false;
                    }
                    declaredSchemaId =
                            UUID.nameUUIDFromBytes(
                                            ("aether-schema:" + type.getQualifiedName())
                                                    .getBytes(StandardCharsets.UTF_8))
                                    .toString();
                } else declaredSchemaId = locked.schemaId.toString();
            }
            schemaId = UUID.fromString(declaredSchemaId);
            if (schemaId.equals(new UUID(0, 0))) throw new IllegalArgumentException("zero UUID");
        } catch (IllegalArgumentException failure) {
            messages.printMessage(Diagnostic.Kind.ERROR, "SCHEMA_ID_INVALID", type);
            return false;
        }
        if (annotation.version() < 1) {
            messages.printMessage(Diagnostic.Kind.ERROR, "SCHEMA_VERSION_INVALID", type);
            return false;
        }
        if (locked != null && !proposing) {
            if (!locked.schemaId.equals(schemaId)) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR, "AETHER_SCHEMA_ID_LOCK_MISMATCH", type);
                return false;
            }
            if (locked.version != annotation.version()) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR,
                        "AETHER_SCHEMA_UPDATE_REQUIRED: lock version "
                                + locked.version
                                + ", source version "
                                + annotation.version()
                                + ". Run: ./gradlew aetherSchemaUpdate",
                        type);
                return false;
            }
        }

        List<FieldModel> fields = new ArrayList<>();
        Set<Integer> fieldIds = new HashSet<>();
        Set<Integer> unavailableIds = new HashSet<>();
        Set<String> sourceFieldNames = new HashSet<>();
        type.getRecordComponents()
                .forEach(component -> sourceFieldNames.add(component.getSimpleName().toString()));
        boolean schemaChanged = false;
        if (locked != null) {
            unavailableIds.addAll(locked.reservedIds);
            locked.fields.values().forEach(field -> unavailableIds.add(field.id));
        }
        boolean valid = true;
        for (RecordComponentElement component : type.getRecordComponents()) {
            AetherField field = component.getAnnotation(AetherField.class);
            int fieldId = field == null ? 0 : field.id();
            LockedField lockedField =
                    locked == null ? null : locked.fields.get(component.getSimpleName().toString());
            if (lockedField == null
                    && locked != null
                    && field != null
                    && !field.previousName().isBlank()) {
                lockedField = locked.fields.get(field.previousName());
                if (lockedField == null) {
                    messages.printMessage(
                            Diagnostic.Kind.ERROR,
                            "AETHER_RENAME_SOURCE_NOT_FOUND: " + field.previousName(),
                            component);
                    valid = false;
                    continue;
                }
                schemaChanged = true;
            }
            if (lockedField == null
                    && locked != null
                    && fieldId == 0
                    && (field == null || field.previousName().isBlank())) {
                List<String> possibleRenames =
                        locked.fields.entrySet().stream()
                                .filter(entry -> !sourceFieldNames.contains(entry.getKey()))
                                .filter(entry -> !entry.getValue().retired)
                                .filter(
                                        entry ->
                                                entry.getValue()
                                                        .javaType
                                                        .equals(component.asType().toString()))
                                .map(java.util.Map.Entry::getKey)
                                .sorted()
                                .toList();
                if (!possibleRenames.isEmpty()) {
                    messages.printMessage(
                            Diagnostic.Kind.ERROR,
                            "AETHER_RENAME_AMBIGUOUS: new field "
                                    + component.getSimpleName()
                                    + " may replace "
                                    + possibleRenames
                                    + "; add @AetherField(previousName=\"...\")",
                            component);
                    valid = false;
                    continue;
                }
            }
            if (fieldId == 0) {
                if (lockedField == null) {
                    if (!proposing) {
                        messages.printMessage(
                                Diagnostic.Kind.ERROR,
                                "Schema update required for "
                                        + type.getQualifiedName()
                                        + ".\n\nNew field:\n  "
                                        + component.getSimpleName()
                                        + "\n\nRun:\n  ./gradlew aetherSchemaUpdate",
                                component);
                        valid = false;
                        continue;
                    }
                    fieldId = nextFieldId(unavailableIds);
                    unavailableIds.add(fieldId);
                    schemaChanged = locked != null;
                } else fieldId = lockedField.id;
            } else if (lockedField != null && lockedField.id != fieldId) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR, "AETHER_FIELD_ID_LOCK_MISMATCH", component);
                valid = false;
            } else if (lockedField == null && locked != null && unavailableIds.contains(fieldId)) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR,
                        "AETHER_REMOVED_FIELD_ID_REUSED: " + fieldId,
                        component);
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
            ResolvedFieldType resolvedType;
            try {
                resolvedType = resolveType(component.asType());
            } catch (IllegalArgumentException failure) {
                messages.printMessage(Diagnostic.Kind.ERROR, failure.getMessage(), component);
                valid = false;
                continue;
            }
            if (resolvedType == null) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR,
                        "FIELD_TYPE_UNSUPPORTED: " + component.asType(),
                        component);
                valid = false;
                continue;
            }
            FieldType fieldType = resolvedType.type;
            int bound = fieldType.fixedBound;
            if (fieldType.variableBound) {
                AetherMaxLength maximum = component.getAnnotation(AetherMaxLength.class);
                if (maximum != null && maximum.value() > 0) bound = maximum.value();
                else if (lockedField != null && lockedField.bound > 0) bound = lockedField.bound;
                else if (fieldType.container) bound = 1024 * 1024;
                else if (proposing) bound = 65_536;
                else {
                    messages.printMessage(Diagnostic.Kind.ERROR, "FIELD_BOUND_REQUIRED", component);
                    valid = false;
                    continue;
                }
            }
            AetherMaxEntries maximumEntries = component.getAnnotation(AetherMaxEntries.class);
            int maxEntries =
                    fieldType == FieldType.OPTIONAL
                            ? 1
                            : maximumEntries == null ? 1024 : maximumEntries.value();
            if (fieldType.container && maxEntries < 1) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR, "CONTAINER_COUNT_BOUND_INVALID", component);
                valid = false;
            }
            if (!proposing
                    && lockedField != null
                    && (!lockedField.javaType.equals(component.asType().toString())
                            || lockedField.bound != bound)) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR,
                        "AETHER_SCHEMA_UPDATE_REQUIRED for field " + component.getSimpleName(),
                        component);
                valid = false;
            }
            if (proposing
                    && lockedField != null
                    && (!lockedField.javaType.equals(component.asType().toString())
                            || lockedField.bound != bound)) schemaChanged = true;
            fields.add(
                    new FieldModel(
                            fieldId,
                            component.getSimpleName().toString(),
                            component.asType().toString(),
                            fieldType,
                            bound,
                            fieldType == FieldType.OPTIONAL
                                    || fieldType == FieldType.OPTIONAL_INSTANT
                                    || (field != null && field.optional()),
                            maxEntries,
                            resolvedType.enumModel,
                            resolvedType.nestedModel));
        }
        if (!valid) return false;

        if (proposing && locked != null) {
            Set<Integer> currentIds = new HashSet<>();
            fields.forEach(current -> currentIds.add(current.id));
            if (locked.fields.values().stream()
                    .map(LockedField::id)
                    .anyMatch(id -> !currentIds.contains(id))) schemaChanged = true;
            if (schemaChanged && annotation.version() <= locked.version) {
                messages.printMessage(
                        Diagnostic.Kind.ERROR,
                        "AETHER_SCHEMA_VERSION_NOT_INCREMENTED: source version must be greater than"
                                + " "
                                + locked.version,
                        type);
                return false;
            }
        }

        fields.sort(Comparator.comparingInt(FieldModel::id));
        String qualifiedName = type.getQualifiedName().toString();
        String packageName =
                processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String descriptor = descriptor(schemaId, annotation.version(), qualifiedName, fields);
        byte[] fingerprint = sha256(descriptor.getBytes(StandardCharsets.UTF_8));
        if (!proposing
                && locked != null
                && !locked.descriptorSha256.isEmpty()
                && !locked.descriptorSha256.equals(hex(fingerprint))) {
            messages.printMessage(
                    Diagnostic.Kind.ERROR,
                    "AETHER_SCHEMA_UPDATE_REQUIRED: descriptor fingerprint differs. Run: ./gradlew"
                            + " aetherSchemaUpdate",
                    type);
            return false;
        }
        int maximumBytes = maximumBytes(fields);
        try {
            writeCodec(
                    type,
                    packageName,
                    simpleName,
                    schemaId,
                    annotation.version(),
                    fields,
                    fingerprint,
                    maximumBytes);
            writeProvider(packageName, simpleName);
            writeDescriptor(schemaId, annotation.version(), descriptor, type);
            generated.add(
                    new GeneratedRecord(
                            qualifiedName,
                            packageName + "." + simpleName + "_AetherCodecProvider",
                            schemaId,
                            annotation.version(),
                            hex(fingerprint)));
            if (proposing)
                proposals.add(
                        new ProposedSchema(
                                qualifiedName,
                                schemaId,
                                annotation.version(),
                                List.copyOf(fields),
                                locked == null ? Set.of() : removedAndReservedIds(locked, fields),
                                locked == null ? java.util.Map.of() : retiredFields(locked, fields),
                                hex(fingerprint)));
            return true;
        } catch (IOException failure) {
            messages.printMessage(
                    Diagnostic.Kind.ERROR,
                    "CODEC_GENERATION_FAILED: " + failure.getMessage(),
                    type);
            return false;
        }
    }

    private boolean isProposalMode() {
        return "PROPOSE"
                .equalsIgnoreCase(
                        processingEnv.getOptions().getOrDefault("aether.schemaMode", "VERIFY"));
    }

    private static int nextFieldId(Set<Integer> unavailable) {
        for (int candidate = 16; candidate <= 536_870_911; candidate++) {
            if (!unavailable.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("AETHER_FIELD_ID_SPACE_EXHAUSTED");
    }

    private static Set<Integer> removedAndReservedIds(
            LockedSchema locked, List<FieldModel> current) {
        Set<Integer> active = new HashSet<>();
        current.forEach(field -> active.add(field.id));
        Set<Integer> reserved = new java.util.TreeSet<>(locked.reservedIds);
        locked.fields.values().stream()
                .map(LockedField::id)
                .filter(id -> !active.contains(id))
                .forEach(reserved::add);
        return Set.copyOf(reserved);
    }

    private static java.util.Map<String, LockedField> retiredFields(
            LockedSchema locked, List<FieldModel> current) {
        Set<Integer> active = new HashSet<>();
        current.forEach(field -> active.add(field.id));
        java.util.Map<String, LockedField> retired = new java.util.TreeMap<>();
        locked.fields.forEach(
                (name, field) -> {
                    if (!active.contains(field.id)) retired.put(name, field);
                });
        return java.util.Map.copyOf(retired);
    }

    private LockedSchema loadLock(TypeElement type) {
        String directory = processingEnv.getOptions().get("aether.schemaDirectory");
        if (directory == null || directory.isBlank()) return null;
        Path index = Path.of(directory).resolve("index.json");
        if (!Files.isRegularFile(index)) return null;
        try {
            String indexJson = Files.readString(index, StandardCharsets.UTF_8);
            String javaType = type.getQualifiedName().toString();
            java.util.regex.Matcher entry =
                    java.util.regex.Pattern.compile(
                                    "\\{\\s*\\\"javaType\\\"\\s*:\\s*\\\""
                                            + java.util.regex.Pattern.quote(javaType)
                                            + "\\\"\\s*,\\s*\\\"schemaId\\\"\\s*:"
                                            + "\\s*\\\"([^\\\"]+)\\\""
                                            + "\\s*,\\s*\\\"currentVersion\\\"\\s*:\\s*(\\d+)"
                                            + "\\s*,\\s*\\\"lockFile\\\"\\s*:"
                                            + "\\s*\\\"([^\\\"]+)\\\"")
                            .matcher(indexJson);
            if (!entry.find()) return null;
            UUID schemaId = UUID.fromString(entry.group(1));
            int version = Integer.parseInt(entry.group(2));
            Path lockPath = Path.of(directory).resolve(entry.group(3)).normalize();
            if (!lockPath.startsWith(Path.of(directory).normalize())
                    || !Files.isRegularFile(lockPath)) {
                throw new IllegalArgumentException("invalid lock path");
            }
            String lockJson = Files.readString(lockPath, StandardCharsets.UTF_8);
            java.util.regex.Matcher field =
                    java.util.regex.Pattern.compile(
                                    "\\{\\s*\\\"id\\\"\\s*:\\s*(\\d+)\\s*,"
                                        + "\\s*\\\"javaName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,"
                                        + "\\s*\\\"wireType\\\"\\s*:\\s*\\\"[^\\\"]+\\\"\\s*,"
                                        + "\\s*\\\"type\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,"
                                        + "\\s*\\\"requiredness\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,"
                                        + "\\s*\\\"maximumEncodedBytes\\\"\\s*:\\s*(\\d+)\\s*\\}")
                            .matcher(lockJson);
            java.util.Map<String, LockedField> fields = new java.util.HashMap<>();
            while (field.find()) {
                fields.put(
                        field.group(2),
                        new LockedField(
                                Integer.parseInt(field.group(1)), field.group(3),
                                Integer.parseInt(field.group(5)),
                                        field.group(4).equals("RETIRED")));
            }
            java.util.regex.Matcher fingerprint =
                    java.util.regex.Pattern.compile(
                                    "\\\"descriptorSha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"")
                            .matcher(lockJson);
            String descriptorSha256 = fingerprint.find() ? fingerprint.group(1) : "";
            Set<Integer> reservedIds = new HashSet<>();
            java.util.regex.Matcher reserved =
                    java.util.regex.Pattern.compile("\\\"reservedFieldIds\\\"\\s*:\\s*\\[([^]]*)]")
                            .matcher(lockJson);
            if (reserved.find() && !reserved.group(1).isBlank()) {
                for (String value : reserved.group(1).split(",")) {
                    reservedIds.add(Integer.parseInt(value.strip()));
                }
            }
            return new LockedSchema(
                    schemaId,
                    version,
                    java.util.Map.copyOf(fields),
                    Set.copyOf(reservedIds),
                    descriptorSha256);
        } catch (IOException | IllegalArgumentException failure) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.ERROR,
                            "AETHER_SCHEMA_LOCK_INVALID for "
                                    + type.getQualifiedName()
                                    + ": "
                                    + failure.getMessage(),
                            type);
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
            int maximumBytes)
            throws IOException {
        String codecName = simpleName + "_AetherCodec";
        JavaFileObject source =
                processingEnv.getFiler().createSourceFile(packageName + "." + codecName, type);
        List<? extends RecordComponentElement> constructorFields = type.getRecordComponents();
        try (Writer output = source.openWriter()) {
            output.write("package " + packageName + ";\n\n");
            output.write(
                    "@javax.annotation.processing.Generated(\""
                            + AetherRecordProcessor.class.getName()
                            + "\")\n");
            output.write(
                    "public final class "
                            + codecName
                            + " implements io.aetherdb.api.typed.ValueCodec<"
                            + simpleName
                            + "> {\n");
            output.write(
                    "  public static final "
                            + codecName
                            + " INSTANCE = new "
                            + codecName
                            + "();\n");
            output.write(
                    "  private static final java.util.UUID SCHEMA_ID = java.util.UUID.fromString(\""
                            + schemaId
                            + "\");\n");
            output.write(
                    "  private static final byte[] FINGERPRINT = new byte[] {"
                            + byteLiterals(fingerprint)
                            + "};\n");
            output.write("  private static final int MAXIMUM_BYTES = " + maximumBytes + ";\n");
            output.write("  private " + codecName + "() {}\n");
            output.write("  @Override public java.util.UUID schemaId() { return SCHEMA_ID; }\n");
            output.write(
                    "  @Override public int currentSchemaVersion() { return " + version + "; }\n");
            output.write(
                    "  @Override public int maximumEncodedSize("
                            + simpleName
                            + " value) { return MAXIMUM_BYTES; }\n");
            output.write(
                    "  @Override public byte[] fingerprint() { return"
                            + " java.util.Arrays.copyOf(FINGERPRINT, FINGERPRINT.length); }\n");
            output.write("  @Override public byte[] encode(" + simpleName + " value) {\n");
            output.write("    java.util.Objects.requireNonNull(value, \"value\");\n");
            output.write(
                    "    var writer = new"
                        + " io.aetherdb.codec.generated.CanonicalRecordWriter(MAXIMUM_BYTES);\n");
            for (FieldModel field : sortedFields) output.write(encodeStatement(field));
            output.write("    return writer.finish();\n  }\n");
            output.write(
                    "  @Override public "
                            + simpleName
                            + " decode(int schemaVersion, byte[] encoded) {\n");
            output.write(
                    "    if (schemaVersion < 1 || schemaVersion > "
                            + version
                            + ") throw new IllegalArgumentException(\"SCHEMA_VERSION_UNSUPPORTED:"
                            + " \" + schemaVersion);\n");
            for (RecordComponentElement component : constructorFields) {
                FieldModel field = findByName(sortedFields, component.getSimpleName().toString());
                output.write(
                        "    "
                                + field.javaType
                                + " "
                                + field.name
                                + " = "
                                + defaultValue(field)
                                + ";\n");
                output.write("    boolean seen_" + field.name + " = false;\n");
            }
            output.write(
                    "    var reader = new"
                            + " io.aetherdb.codec.generated.CanonicalRecordReader(encoded,"
                            + " MAXIMUM_BYTES);\n");
            output.write("    while (reader.next()) {\n      switch (reader.fieldId()) {\n");
            for (FieldModel field : sortedFields) output.write(decodeCase(field));
            output.write("        default -> { /* validated unknown field skipped */ }\n");
            output.write("      }\n    }\n");
            for (RecordComponentElement component : constructorFields) {
                String name = component.getSimpleName().toString();
                FieldModel field = findByName(sortedFields, name);
                if (!field.optional)
                    output.write(
                            "    if (!seen_"
                                    + name
                                    + ") throw new IllegalArgumentException(\"missing required"
                                    + " field: "
                                    + name
                                    + "\");\n");
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
        JavaFileObject source =
                processingEnv.getFiler().createSourceFile(packageName + "." + providerName);
        try (Writer output = source.openWriter()) {
            output.write("package " + packageName + ";\n\n");
            output.write(
                    "@javax.annotation.processing.Generated(\""
                            + AetherRecordProcessor.class.getName()
                            + "\")\n");
            output.write(
                    "public final class "
                            + providerName
                            + " implements io.aetherdb.codec.generated.GeneratedCodecProvider {\n");
            output.write(
                    "  @Override public Class<?> recordType() { return "
                            + simpleName
                            + ".class; }\n");
            output.write(
                    "  @Override public io.aetherdb.api.typed.ValueCodec<?> codec() { return "
                            + simpleName
                            + "_AetherCodec.INSTANCE; }\n}\n");
        }
    }

    private void writeDescriptor(
            UUID schemaId, int version, String descriptor, TypeElement originating)
            throws IOException {
        String resource = "META-INF/aether/schemas/" + schemaId + "/" + version + ".aesch";
        try (Writer output =
                processingEnv
                        .getFiler()
                        .createResource(StandardLocation.CLASS_OUTPUT, "", resource, originating)
                        .openWriter()) {
            output.write(descriptor);
        }
    }

    private void writeRegistrationResources() {
        generated.sort(Comparator.comparing(GeneratedRecord::qualifiedName));
        Filer filer = processingEnv.getFiler();
        try (Writer services =
                        filer.createResource(
                                        StandardLocation.CLASS_OUTPUT,
                                        "",
                                        "META-INF/services/"
                                            + "io.aetherdb.codec.generated.GeneratedCodecProvider")
                                .openWriter();
                Writer index =
                        filer.createResource(
                                        StandardLocation.CLASS_OUTPUT,
                                        "",
                                        "META-INF/aether/generated-codecs.idx")
                                .openWriter()) {
            for (GeneratedRecord record : generated) {
                services.write(record.providerName + "\n");
                index.write(
                        record.qualifiedName
                                + "|"
                                + record.schemaId
                                + "|"
                                + record.version
                                + "|"
                                + record.providerName
                                + "|"
                                + record.fingerprint
                                + "\n");
            }
        } catch (IOException failure) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.ERROR,
                            "CODEC_GENERATION_FAILED registration: " + failure.getMessage());
        }
    }

    private void writeSchemaProposals() {
        if (!isProposalMode() || proposals.isEmpty()) return;
        String configured = processingEnv.getOptions().get("aether.schemaProposalDirectory");
        if (configured == null || configured.isBlank()) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.ERROR, "AETHER_SCHEMA_PROPOSAL_DIRECTORY_MISSING");
            return;
        }
        Path directory = Path.of(configured).normalize();
        try {
            Files.createDirectories(directory);
            proposals.sort(Comparator.comparing(ProposedSchema::javaType));
            StringBuilder index =
                    new StringBuilder("{\n  \"formatVersion\": 1,\n  \"schemas\": [\n");
            for (int proposalIndex = 0; proposalIndex < proposals.size(); proposalIndex++) {
                ProposedSchema proposal = proposals.get(proposalIndex);
                String fileName = proposal.schemaId + ".schema.json";
                Files.writeString(
                        directory.resolve(fileName),
                        proposalJson(proposal),
                        StandardCharsets.UTF_8);
                if (proposalIndex > 0) index.append(",\n");
                index.append("    {\n")
                        .append("      \"javaType\": \"")
                        .append(proposal.javaType)
                        .append("\",\n")
                        .append("      \"schemaId\": \"")
                        .append(proposal.schemaId)
                        .append("\",\n")
                        .append("      \"currentVersion\": ")
                        .append(proposal.version)
                        .append(",\n")
                        .append("      \"lockFile\": \"")
                        .append(fileName)
                        .append("\"\n")
                        .append("    }");
            }
            index.append("\n  ]\n}\n");
            Files.writeString(directory.resolve("index.json"), index, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.ERROR,
                            "AETHER_SCHEMA_PROPOSAL_FAILED: " + failure.getMessage());
        }
    }

    private static String proposalJson(ProposedSchema proposal) {
        StringBuilder json =
                new StringBuilder("{\n")
                        .append("  \"formatVersion\": 1,\n")
                        .append("  \"schemaId\": \"")
                        .append(proposal.schemaId)
                        .append("\",\n")
                        .append("  \"javaType\": \"")
                        .append(proposal.javaType)
                        .append("\",\n")
                        .append("  \"currentVersion\": ")
                        .append(proposal.version)
                        .append(",\n")
                        .append("  \"unknownFieldPolicy\": \"SKIP\",\n")
                        .append("  \"reservedFieldIds\": [");
        proposal.reservedIds.stream()
                .sorted()
                .forEachOrdered(
                        id -> {
                            if (json.charAt(json.length() - 1) != '[') json.append(", ");
                            json.append(id);
                        });
        json.append("],\n  \"retiredFields\": [");
        int retiredIndex = 0;
        for (var retired : proposal.retiredFields.entrySet()) {
            if (retiredIndex++ > 0) json.append(',');
            LockedField field = retired.getValue();
            json.append("\n    {\n")
                    .append("      \"id\": ")
                    .append(field.id)
                    .append(",\n")
                    .append("      \"javaName\": \"")
                    .append(retired.getKey())
                    .append("\",\n")
                    .append("      \"wireType\": \"")
                    .append(wireName(field.javaType))
                    .append("\",\n")
                    .append("      \"type\": \"")
                    .append(field.javaType)
                    .append("\",\n")
                    .append("      \"requiredness\": \"RETIRED\",\n")
                    .append("      \"maximumEncodedBytes\": ")
                    .append(field.bound)
                    .append("\n")
                    .append("    }");
        }
        if (retiredIndex > 0) json.append('\n');
        json.append("  ],\n  \"versions\": [\n    {\n")
                .append("      \"version\": ")
                .append(proposal.version)
                .append(",\n")
                .append("      \"payloadFormat\": \"AER1\",\n")
                .append("      \"fields\": [\n");
        for (int index = 0; index < proposal.fields.size(); index++) {
            FieldModel field = proposal.fields.get(index);
            if (index > 0) json.append(",\n");
            json.append("        {\n")
                    .append("          \"id\": ")
                    .append(field.id)
                    .append(",\n")
                    .append("          \"javaName\": \"")
                    .append(field.name)
                    .append("\",\n")
                    .append("          \"wireType\": \"")
                    .append(field.type.wireName())
                    .append("\",\n")
                    .append("          \"type\": \"")
                    .append(field.javaType)
                    .append("\",\n")
                    .append("          \"requiredness\": \"")
                    .append(field.optional ? "OPTIONAL" : "REQUIRED")
                    .append("\",\n")
                    .append("          \"maximumEncodedBytes\": ")
                    .append(field.bound)
                    .append("\n")
                    .append("        }");
        }
        return json.append("\n      ],\n")
                .append("      \"descriptorSha256\": \"")
                .append(proposal.fingerprint)
                .append("\",\n")
                .append("      \"compatibilityFromPrevious\": \"")
                .append(proposal.version == 1 ? "INITIAL" : "COMPATIBLE")
                .append("\"\n    }\n  ]\n}\n")
                .toString();
    }

    private static String wireName(String javaType) {
        if (isContainerName(javaType)) return "CONTAINER";
        return switch (javaType) {
            case "boolean" -> "BOOL";
            case "long", "int" -> "SIGNED_VARINT";
            case "double" -> "FIXED64";
            case "java.lang.String" -> "STRING_UTF8";
            case "java.util.UUID" -> "UUID128";
            case "java.time.Instant" -> "TEMPORAL";
            case "java.util.Optional<java.time.Instant>" -> "TEMPORAL";
            default -> "UNKNOWN";
        };
    }

    private static String encodeStatement(FieldModel field) {
        if (field.type == FieldType.ENUM) {
            StringBuilder expression =
                    new StringBuilder("switch (value.").append(field.name).append("()) {");
            for (EnumConstantModel constant : field.enumModel.constants)
                expression
                        .append(" case ")
                        .append(constant.name)
                        .append(" -> ")
                        .append(constant.id)
                        .append('L')
                        .append(';');
            expression.append(" }");
            return "    writer.field("
                    + field.id
                    + ", io.aetherdb.codec.generated.WireType.ENUM, "
                    + "io.aetherdb.codec.generated.CanonicalRecordWriter.signedLong("
                    + expression
                    + "));\n";
        }
        if (field.type == FieldType.NESTED) {
            return "    writer.field("
                    + field.id
                    + ", io.aetherdb.codec.generated.WireType.NESTED, "
                    + "io.aetherdb.codec.generated.CanonicalRecordWriter.nested("
                    + field.javaType
                    + "_AetherCodec.INSTANCE, value."
                    + field.name
                    + "(), "
                    + field.bound
                    + "));\n";
        }
        if (field.type.container) {
            return "    writer.field("
                    + field.id
                    + ", io.aetherdb.codec.generated.WireType.CONTAINER, "
                    + containerExpression(
                            field.javaType,
                            "value." + field.name + "()",
                            field.bound,
                            field.maximumEntries,
                            true)
                    + ");\n";
        }
        if (field.type == FieldType.OPTIONAL_INSTANT) {
            return "    value."
                    + field.name
                    + "().ifPresent(item -> writer.field("
                    + field.id
                    + ", io.aetherdb.codec.generated.WireType.TEMPORAL, "
                    + "io.aetherdb.codec.generated.CanonicalRecordWriter.instant(item)));\n";
        }
        return "    writer.field("
                + field.id
                + ", "
                + field.type.wireConstant
                + ", "
                + field.type.writerExpression(field.name, field.bound)
                + ");\n";
    }

    private static String decodeCase(FieldModel field) {
        if (field.type == FieldType.ENUM) {
            StringBuilder expression =
                    new StringBuilder(
                            "switch (java.lang.Math.toIntExact(reader.signedLongValue())) {");
            for (EnumConstantModel constant : field.enumModel.constants)
                expression
                        .append(" case ")
                        .append(constant.id)
                        .append(" -> ")
                        .append(field.javaType)
                        .append('.')
                        .append(constant.name)
                        .append(';');
            expression
                    .append(
                            " default -> throw new IllegalArgumentException(\"ENUM_VALUE_UNKNOWN at"
                                    + " field ")
                    .append(field.id)
                    .append("\"); }");
            return "        case "
                    + field.id
                    + " -> { reader.requireWireType(io.aetherdb.codec.generated.WireType.ENUM); "
                    + field.name
                    + " = "
                    + expression
                    + "; seen_"
                    + field.name
                    + " = true; }\n";
        }
        if (field.type == FieldType.NESTED) {
            return "        case "
                    + field.id
                    + " -> { reader.requireWireType(io.aetherdb.codec.generated.WireType.NESTED); "
                    + field.name
                    + " = reader.nestedValue("
                    + field.javaType
                    + "_AetherCodec.INSTANCE, "
                    + field.bound
                    + "); seen_"
                    + field.name
                    + " = true; }\n";
        }
        if (field.type.container) {
            return "        case "
                    + field.id
                    + " -> {"
                    + " reader.requireWireType(io.aetherdb.codec.generated.WireType.CONTAINER); "
                    + field.name
                    + " = "
                    + containerExpression(
                            field.javaType,
                            "reader.rawPayload()",
                            field.bound,
                            field.maximumEntries,
                            false)
                    + "; seen_"
                    + field.name
                    + " = true; }\n";
        }
        return "        case "
                + field.id
                + " -> { reader.requireWireType("
                + field.type.wireConstant
                + "); "
                + field.name
                + " = "
                + field.type.readerExpression(field.bound)
                + "; seen_"
                + field.name
                + " = true; }\n";
    }

    private static String containerExpression(
            String type, String value, int bound, int maximumEntries, boolean encoding) {
        List<String> arguments = genericArguments(type);
        String codecClass = "io.aetherdb.codec.generated.CanonicalContainerCodec";
        if (type.startsWith("java.util.Optional<")) {
            String codec = elementCodec(arguments.get(0), bound, maximumEntries);
            return codecClass + ".optional(" + value + ", " + codec + ", " + bound + ")";
        }
        if (type.startsWith("java.util.List<")) {
            String codec = elementCodec(arguments.get(0), bound, maximumEntries);
            return codecClass
                    + ".list("
                    + value
                    + ", "
                    + maximumEntries
                    + ", "
                    + bound
                    + ", "
                    + codec
                    + ")";
        }
        if (type.startsWith("java.util.Set<")) {
            String codec = elementCodec(arguments.get(0), bound, maximumEntries);
            return codecClass
                    + ".set("
                    + value
                    + ", "
                    + maximumEntries
                    + ", "
                    + bound
                    + ", "
                    + codec
                    + ")";
        }
        if (type.startsWith("java.util.Map<")) {
            String keyCodec = elementCodec(arguments.get(0), bound, maximumEntries);
            String valueCodec = elementCodec(arguments.get(1), bound, maximumEntries);
            return codecClass
                    + ".map("
                    + value
                    + ", "
                    + maximumEntries
                    + ", "
                    + bound
                    + ", "
                    + keyCodec
                    + ", "
                    + valueCodec
                    + ")";
        }
        throw new IllegalArgumentException("not a supported container type: " + type);
    }

    private static String elementCodec(String type, int bound, int maximumEntries) {
        String encoded = elementEncodeExpression(type, "item", bound, maximumEntries);
        String decoded = elementDecodeExpression(type, "encoded", bound, maximumEntries);
        return "new io.aetherdb.codec.generated.CanonicalContainerCodec.ElementCodec<"
                + type
                + ">() { "
                + "public byte[] encode("
                + type
                + " item) { return "
                + encoded
                + "; } "
                + "public "
                + type
                + " decode(byte[] encoded) { return "
                + decoded
                + "; } }";
    }

    private static String elementEncodeExpression(
            String type, String value, int bound, int maximumEntries) {
        if (isContainerName(type))
            return containerExpression(type, value, bound, maximumEntries, true);
        String writer = "io.aetherdb.codec.generated.CanonicalRecordWriter.";
        return switch (type) {
            case "java.lang.Boolean" -> writer + "bool(" + value + ")";
            case "java.lang.Byte" -> writer + "signedByte(" + value + ")";
            case "java.lang.Short", "java.lang.Integer", "java.lang.Long" ->
                    writer + "signedLong(" + value + ")";
            case "java.lang.Float" -> writer + "fixed32(" + value + ")";
            case "java.lang.Double" -> writer + "fixed64(" + value + ")";
            case "java.lang.Character" -> writer + "character(" + value + ")";
            case "java.lang.String" -> writer + "string(" + value + ", " + bound + ")";
            case "java.util.UUID" -> writer + "uuid(" + value + ")";
            case "java.time.Instant" -> writer + "instant(" + value + ")";
            case "java.time.LocalDate" -> writer + "localDate(" + value + ")";
            case "java.time.LocalTime" -> writer + "localTime(" + value + ")";
            case "java.time.LocalDateTime" -> writer + "localDateTime(" + value + ")";
            case "java.time.Duration" -> writer + "duration(" + value + ")";
            case "java.math.BigInteger" -> writer + "bigInteger(" + value + ", " + bound + ")";
            case "java.math.BigDecimal" -> writer + "bigDecimal(" + value + ", " + bound + ")";
            case "byte[]" -> writer + "bytes(" + value + ", " + bound + ")";
            default -> throw new IllegalArgumentException("unsupported container element: " + type);
        };
    }

    private static String elementDecodeExpression(
            String type, String value, int bound, int maximumEntries) {
        if (isContainerName(type))
            return containerExpression(type, value, bound, maximumEntries, false);
        String reader = "io.aetherdb.codec.generated.CanonicalElementCodecs.";
        return switch (type) {
            case "java.lang.Boolean" -> reader + "bool(" + value + ")";
            case "java.lang.Byte" -> reader + "signedByte(" + value + ")";
            case "java.lang.Short" -> reader + "signedShort(" + value + ")";
            case "java.lang.Integer" -> reader + "signedInt(" + value + ")";
            case "java.lang.Long" -> reader + "signedLong(" + value + ")";
            case "java.lang.Float" -> reader + "fixed32(" + value + ")";
            case "java.lang.Double" -> reader + "fixed64(" + value + ")";
            case "java.lang.Character" -> reader + "character(" + value + ")";
            case "java.lang.String" -> reader + "string(" + value + ", " + bound + ")";
            case "java.util.UUID" -> reader + "uuid(" + value + ")";
            case "java.time.Instant" -> reader + "instant(" + value + ")";
            case "java.time.LocalDate" -> reader + "localDate(" + value + ")";
            case "java.time.LocalTime" -> reader + "localTime(" + value + ")";
            case "java.time.LocalDateTime" -> reader + "localDateTime(" + value + ")";
            case "java.time.Duration" -> reader + "duration(" + value + ")";
            case "java.math.BigInteger" -> reader + "bigInteger(" + value + ", " + bound + ")";
            case "java.math.BigDecimal" -> reader + "bigDecimal(" + value + ", " + bound + ")";
            case "byte[]" -> reader + "bytes(" + value + ", " + bound + ")";
            default -> throw new IllegalArgumentException("unsupported container element: " + type);
        };
    }

    private static boolean isContainerName(String type) {
        return type.startsWith("java.util.Optional<")
                || type.startsWith("java.util.List<")
                || type.startsWith("java.util.Set<")
                || type.startsWith("java.util.Map<");
    }

    private static FieldModel findByName(List<FieldModel> fields, String name) {
        return fields.stream().filter(field -> field.name.equals(name)).findFirst().orElseThrow();
    }

    private static String defaultValue(FieldModel field) {
        if (field.type == FieldType.OPTIONAL || field.type == FieldType.OPTIONAL_INSTANT) {
            return "java.util.Optional.empty()";
        }
        if (!Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char")
                .contains(field.javaType)) return "null";
        return switch (field.type) {
            case LONG -> "0L";
            case INT, BYTE, SHORT, CHAR -> "0";
            case BOOLEAN -> "false";
            case FLOAT -> "0.0f";
            case DOUBLE -> "0.0d";
            default -> "null";
        };
    }

    private ResolvedFieldType resolveType(TypeMirror type) {
        if (type.getKind() == TypeKind.LONG) return resolved(FieldType.LONG);
        if (type.getKind() == TypeKind.INT) return resolved(FieldType.INT);
        if (type.getKind() == TypeKind.BYTE) return resolved(FieldType.BYTE);
        if (type.getKind() == TypeKind.SHORT) return resolved(FieldType.SHORT);
        if (type.getKind() == TypeKind.BOOLEAN) return resolved(FieldType.BOOLEAN);
        if (type.getKind() == TypeKind.FLOAT) return resolved(FieldType.FLOAT);
        if (type.getKind() == TypeKind.DOUBLE) return resolved(FieldType.DOUBLE);
        if (type.getKind() == TypeKind.CHAR) return resolved(FieldType.CHAR);
        String name = type.toString();
        if (name.startsWith("java.util.Optional<") && supportedContainerType(name))
            return resolved(FieldType.OPTIONAL);
        if (name.startsWith("java.util.List<") && supportedContainerType(name))
            return resolved(FieldType.LIST);
        if (name.startsWith("java.util.Set<") && supportedContainerType(name))
            return resolved(FieldType.SET);
        if (name.startsWith("java.util.Map<") && supportedContainerType(name))
            return resolved(FieldType.MAP);
        ResolvedFieldType scalar =
                switch (name) {
                    case "java.lang.Boolean" -> resolved(FieldType.BOOLEAN);
                    case "java.lang.Byte" -> resolved(FieldType.BYTE);
                    case "java.lang.Short" -> resolved(FieldType.SHORT);
                    case "java.lang.Integer" -> resolved(FieldType.INT);
                    case "java.lang.Long" -> resolved(FieldType.LONG);
                    case "java.lang.Float" -> resolved(FieldType.FLOAT);
                    case "java.lang.Double" -> resolved(FieldType.DOUBLE);
                    case "java.lang.Character" -> resolved(FieldType.CHAR);
                    case "java.lang.String" -> resolved(FieldType.STRING);
                    case "java.util.UUID" -> resolved(FieldType.UUID);
                    case "java.time.Instant" -> resolved(FieldType.INSTANT);
                    case "java.time.LocalDate" -> resolved(FieldType.LOCAL_DATE);
                    case "java.time.LocalTime" -> resolved(FieldType.LOCAL_TIME);
                    case "java.time.LocalDateTime" -> resolved(FieldType.LOCAL_DATE_TIME);
                    case "java.time.Duration" -> resolved(FieldType.DURATION);
                    case "java.math.BigInteger" -> resolved(FieldType.BIG_INTEGER);
                    case "java.math.BigDecimal" -> resolved(FieldType.BIG_DECIMAL);
                    case "byte[]" -> resolved(FieldType.BYTE_ARRAY);
                    default -> null;
                };
        if (scalar != null) return scalar;
        if (type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element) {
            AetherRecord nested = element.getAnnotation(AetherRecord.class);
            if (nested != null) {
                String identity = nested.schemaId();
                if (identity.isBlank()) {
                    LockedSchema lock = loadLock(element);
                    if (lock == null)
                        throw new IllegalArgumentException(
                                "NESTED_SCHEMA_LOCK_MISSING: " + element.getQualifiedName());
                    identity = lock.schemaId.toString();
                }
                try {
                    UUID schemaId = UUID.fromString(identity);
                    return new ResolvedFieldType(
                            FieldType.NESTED, null, new NestedModel(schemaId, nested.version()));
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException(
                            "NESTED_SCHEMA_ID_INVALID: " + element.getQualifiedName());
                }
            }
            if (element.getAnnotation(AetherEnum.class) != null) {
                List<EnumConstantModel> constants = new ArrayList<>();
                Set<Integer> identities = new HashSet<>();
                for (Element enclosed : element.getEnclosedElements())
                    if (enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
                        AetherEnumValue annotation = enclosed.getAnnotation(AetherEnumValue.class);
                        if (annotation == null || annotation.value() < 1) {
                            throw new IllegalArgumentException(
                                    "ENUM_VALUE_ID_REQUIRED: "
                                            + element.getQualifiedName()
                                            + '.'
                                            + enclosed.getSimpleName());
                        }
                        if (!identities.add(annotation.value()))
                            throw new IllegalArgumentException(
                                    "ENUM_VALUE_ID_DUPLICATE: " + annotation.value());
                        constants.add(
                                new EnumConstantModel(
                                        enclosed.getSimpleName().toString(), annotation.value()));
                    }
                if (constants.isEmpty())
                    throw new IllegalArgumentException(
                            "AETHER_ENUM_EMPTY: " + element.getQualifiedName());
                return new ResolvedFieldType(
                        FieldType.ENUM,
                        new EnumModel(
                                element.getQualifiedName().toString(), List.copyOf(constants)),
                        null);
            }
        }
        return null;
    }

    private static ResolvedFieldType resolved(FieldType type) {
        return new ResolvedFieldType(type, null, null);
    }

    private static boolean supportedContainerType(String type) {
        List<String> arguments = genericArguments(type);
        if (type.startsWith("java.util.Map<") ? arguments.size() != 2 : arguments.size() != 1)
            return false;
        return arguments.stream().allMatch(AetherRecordProcessor::supportedElementType);
    }

    private static boolean supportedElementType(String type) {
        if (type.startsWith("java.util.Optional<")
                || type.startsWith("java.util.List<")
                || type.startsWith("java.util.Set<")
                || type.startsWith("java.util.Map<")) {
            return supportedContainerType(type);
        }
        return Set.of(
                        "java.lang.Boolean",
                        "java.lang.Byte",
                        "java.lang.Short",
                        "java.lang.Integer",
                        "java.lang.Long",
                        "java.lang.Float",
                        "java.lang.Double",
                        "java.lang.Character",
                        "java.lang.String",
                        "java.util.UUID",
                        "java.time.Instant",
                        "java.time.LocalDate",
                        "java.time.LocalTime",
                        "java.time.LocalDateTime",
                        "java.time.Duration",
                        "java.math.BigInteger",
                        "java.math.BigDecimal",
                        "byte[]")
                .contains(type);
    }

    private static List<String> genericArguments(String type) {
        int open = type.indexOf('<');
        if (open < 0 || !type.endsWith(">")) return List.of();
        List<String> result = new ArrayList<>();
        int depth = 0, start = open + 1;
        for (int index = start; index < type.length() - 1; index++) {
            char character = type.charAt(index);
            if (character == '<') depth++;
            else if (character == '>') depth--;
            else if (character == ',' && depth == 0) {
                result.add(type.substring(start, index).strip());
                start = index + 1;
            }
        }
        result.add(type.substring(start, type.length() - 1).strip());
        return List.copyOf(result);
    }

    private static String descriptor(
            UUID schemaId, int version, String type, List<FieldModel> fields) {
        StringBuilder descriptor =
                new StringBuilder("AETHER_SCHEMA_DESCRIPTOR_V1\n")
                        .append("schemaId=")
                        .append(schemaId)
                        .append('\n')
                        .append("version=")
                        .append(version)
                        .append('\n')
                        .append("type=")
                        .append(type)
                        .append('\n')
                        .append("payloadFormat=1\nunknownFields=SKIP\n");
        for (FieldModel field : fields) {
            descriptor
                    .append("field=")
                    .append(field.id)
                    .append('|')
                    .append(field.name)
                    .append('|')
                    .append(field.type)
                    .append('|')
                    .append(field.bound)
                    .append('|')
                    .append(field.optional);
            if (field.type.container) descriptor.append('|').append(field.maximumEntries);
            descriptor.append('\n');
            if (field.enumModel != null) {
                descriptor.append("detail=").append(field.id).append("|enum|");
                for (int index = 0; index < field.enumModel.constants.size(); index++) {
                    if (index > 0) descriptor.append(',');
                    EnumConstantModel constant = field.enumModel.constants.get(index);
                    descriptor.append(constant.id).append(':').append(constant.name);
                }
                descriptor.append('\n');
            }
            if (field.nestedModel != null)
                descriptor
                        .append("detail=")
                        .append(field.id)
                        .append("|nested|")
                        .append(field.nestedModel.schemaId)
                        .append(':')
                        .append(field.nestedModel.version)
                        .append('\n');
        }
        return descriptor.toString();
    }

    private static int maximumBytes(List<FieldModel> fields) {
        int maximum = 24;
        for (FieldModel field : fields) maximum = Math.addExact(maximum, 22 + field.bound);
        return maximum;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
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
        BYTE(1, "io.aetherdb.codec.generated.WireType.SIGNED_BYTE"),
        SHORT(3, "io.aetherdb.codec.generated.WireType.SIGNED_VARINT"),
        LONG(10, "io.aetherdb.codec.generated.WireType.SIGNED_VARINT"),
        INT(5, "io.aetherdb.codec.generated.WireType.SIGNED_VARINT"),
        FLOAT(4, "io.aetherdb.codec.generated.WireType.FIXED32"),
        DOUBLE(8, "io.aetherdb.codec.generated.WireType.FIXED64"),
        CHAR(3, "io.aetherdb.codec.generated.WireType.UNSIGNED_VARINT"),
        STRING(-1, "io.aetherdb.codec.generated.WireType.STRING_UTF8", true),
        UUID(16, "io.aetherdb.codec.generated.WireType.UUID128"),
        INSTANT(20, "io.aetherdb.codec.generated.WireType.TEMPORAL"),
        LOCAL_DATE(10, "io.aetherdb.codec.generated.WireType.TEMPORAL"),
        LOCAL_TIME(10, "io.aetherdb.codec.generated.WireType.TEMPORAL"),
        LOCAL_DATE_TIME(20, "io.aetherdb.codec.generated.WireType.TEMPORAL"),
        DURATION(20, "io.aetherdb.codec.generated.WireType.TEMPORAL"),
        BIG_INTEGER(-1, "io.aetherdb.codec.generated.WireType.DECIMAL", true),
        BIG_DECIMAL(-1, "io.aetherdb.codec.generated.WireType.DECIMAL", true),
        BYTE_ARRAY(-1, "io.aetherdb.codec.generated.WireType.BYTES", true),
        OPTIONAL(-1, "io.aetherdb.codec.generated.WireType.CONTAINER", true, true),
        LIST(-1, "io.aetherdb.codec.generated.WireType.CONTAINER", true, true),
        SET(-1, "io.aetherdb.codec.generated.WireType.CONTAINER", true, true),
        MAP(-1, "io.aetherdb.codec.generated.WireType.CONTAINER", true, true),
        ENUM(5, "io.aetherdb.codec.generated.WireType.ENUM"),
        NESTED(-1, "io.aetherdb.codec.generated.WireType.NESTED", true),
        OPTIONAL_INSTANT(20, "io.aetherdb.codec.generated.WireType.TEMPORAL");

        private final int fixedBound;
        private final String wireConstant;
        private final boolean variableBound;
        private final boolean container;

        FieldType(int fixedBound, String wireConstant) {
            this(fixedBound, wireConstant, false);
        }

        FieldType(int fixedBound, String wireConstant, boolean variableBound) {
            this(fixedBound, wireConstant, variableBound, false);
        }

        FieldType(int fixedBound, String wireConstant, boolean variableBound, boolean container) {
            this.fixedBound = fixedBound;
            this.wireConstant = wireConstant;
            this.variableBound = variableBound;
            this.container = container;
        }

        String writerExpression(String name, int bound) {
            return switch (this) {
                case BOOLEAN ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.bool(value."
                                + name
                                + "())";
                case BYTE ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.signedByte(value."
                                + name
                                + "())";
                case LONG, INT, SHORT ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.signedLong(value."
                                + name
                                + "())";
                case FLOAT ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.fixed32(value."
                                + name
                                + "())";
                case DOUBLE ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.fixed64(value."
                                + name
                                + "())";
                case CHAR ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.character(value."
                                + name
                                + "())";
                case STRING ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.string(value."
                                + name
                                + "(), "
                                + bound
                                + ")";
                case UUID ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.uuid(value."
                                + name
                                + "())";
                case INSTANT ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.instant(value."
                                + name
                                + "())";
                case LOCAL_DATE ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.localDate(value."
                                + name
                                + "())";
                case LOCAL_TIME ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.localTime(value."
                                + name
                                + "())";
                case LOCAL_DATE_TIME ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.localDateTime(value."
                                + name
                                + "())";
                case DURATION ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.duration(value."
                                + name
                                + "())";
                case BIG_INTEGER ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.bigInteger(value."
                                + name
                                + "(), "
                                + bound
                                + ")";
                case BIG_DECIMAL ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.bigDecimal(value."
                                + name
                                + "(), "
                                + bound
                                + ")";
                case BYTE_ARRAY ->
                        "io.aetherdb.codec.generated.CanonicalRecordWriter.bytes(value."
                                + name
                                + "(), "
                                + bound
                                + ")";
                case OPTIONAL, LIST, SET, MAP ->
                        throw new IllegalStateException(
                                "container writer uses generated element codecs");
                case ENUM, NESTED ->
                        throw new IllegalStateException(
                                "structured writer is generated separately");
                case OPTIONAL_INSTANT ->
                        throw new IllegalStateException("optional writer is conditional");
            };
        }

        String readerExpression(int bound) {
            return switch (this) {
                case BOOLEAN -> "reader.boolValue()";
                case BYTE -> "reader.signedByteValue()";
                case SHORT -> "reader.signedShortValue()";
                case LONG -> "reader.signedLongValue()";
                case INT -> "java.lang.Math.toIntExact(reader.signedLongValue())";
                case FLOAT -> "reader.fixed32Value()";
                case DOUBLE -> "reader.fixed64Value()";
                case CHAR -> "reader.characterValue()";
                case STRING -> "reader.stringValue(" + bound + ")";
                case UUID -> "reader.uuidValue()";
                case INSTANT -> "reader.instantValue()";
                case LOCAL_DATE -> "reader.localDateValue()";
                case LOCAL_TIME -> "reader.localTimeValue()";
                case LOCAL_DATE_TIME -> "reader.localDateTimeValue()";
                case DURATION -> "reader.durationValue()";
                case BIG_INTEGER -> "reader.bigIntegerValue(" + bound + ")";
                case BIG_DECIMAL -> "reader.bigDecimalValue(" + bound + ")";
                case BYTE_ARRAY -> "reader.bytesValue(" + bound + ")";
                case OPTIONAL, LIST, SET, MAP ->
                        throw new IllegalStateException(
                                "container reader uses generated element codecs");
                case ENUM, NESTED ->
                        throw new IllegalStateException(
                                "structured reader is generated separately");
                case OPTIONAL_INSTANT -> "java.util.Optional.of(reader.instantValue())";
            };
        }

        String wireName() {
            return switch (this) {
                case BOOLEAN -> "BOOL";
                case BYTE -> "SIGNED_BYTE";
                case LONG, INT, SHORT -> "SIGNED_VARINT";
                case CHAR -> "UNSIGNED_VARINT";
                case FLOAT -> "FIXED32";
                case DOUBLE -> "FIXED64";
                case STRING -> "STRING_UTF8";
                case UUID -> "UUID128";
                case INSTANT, OPTIONAL_INSTANT, LOCAL_DATE, LOCAL_TIME, LOCAL_DATE_TIME, DURATION ->
                        "TEMPORAL";
                case BIG_INTEGER, BIG_DECIMAL -> "DECIMAL";
                case BYTE_ARRAY -> "BYTES";
                case OPTIONAL, LIST, SET, MAP -> "CONTAINER";
                case ENUM -> "ENUM";
                case NESTED -> "NESTED";
            };
        }
    }

    private record FieldModel(
            int id,
            String name,
            String javaType,
            FieldType type,
            int bound,
            boolean optional,
            int maximumEntries,
            EnumModel enumModel,
            NestedModel nestedModel) {}

    private record ResolvedFieldType(
            FieldType type, EnumModel enumModel, NestedModel nestedModel) {}

    private record EnumModel(String javaType, List<EnumConstantModel> constants) {}

    private record EnumConstantModel(String name, int id) {}

    private record NestedModel(UUID schemaId, int version) {}

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
            Set<Integer> reservedIds,
            String descriptorSha256) {}

    private record LockedField(int id, String javaType, int bound, boolean retired) {}

    private record ProposedSchema(
            String javaType,
            UUID schemaId,
            int version,
            List<FieldModel> fields,
            Set<Integer> reservedIds,
            java.util.Map<String, LockedField> retiredFields,
            String fingerprint) {}
}
