package org.moddingx.cfupdatechecker.version;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moandjiezana.toml.Toml;
import org.apache.commons.lang3.tuple.Pair;
import org.moddingx.cfupdatechecker.Util;
import org.moddingx.cfupdatechecker.cache.FileCache;
import org.moddingx.cursewrapper.api.response.FileInfo;

import java.io.*;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class VersionResolver {

    private static final Pattern MANIFEST_REGEX = Pattern.compile("^\\s*Implementation-Version\\s*:\\s*(.*?)\\s*$");
    private static final List<Pair<String, Function<byte[], Optional<String>>>> FILE_RESOLVERS = List.of(
            Pair.of("META-INF/mods.toml", VersionResolver::versionFromToml),
            Pair.of("mcmod.info", VersionResolver::versionFromLegacy),
            Pair.of("META-INF/MANIFEST.MF", VersionResolver::versionFromManifest),
            Pair.of("module-info.class", VersionResolver::versionFromModule)
    );
    private static final Set<String> FILE_NAMES = FILE_RESOLVERS.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

    public static Optional<String> getVersion(FileInfo file, FileCache cache) {
        String resolved = cache.version(new FileCache.FileKey(file), () -> {
            try {
                return getVersionFromMetadata(file);
            } catch (Exception e) {
                System.err.println("Failed to get version for '" + file.name() + "'");
                e.printStackTrace();
                return "INVALID";
            }
        });
        if (resolved.equals("INVALID")) {
            return Optional.empty();
        } else {
            return Optional.of(resolved);
        }
    }

    private static String getVersionFromMetadata(FileInfo file) throws IOException {
        ZipInputStream zin = new ZipInputStream(downloadUrl(file).openStream());
        Map<String, byte[]> dataMap = new HashMap<>();
        ZipEntry entry = zin.getNextEntry();
        while (entry != null) {
            String name = entry.getName().startsWith("/") ? entry.getName().substring(1) : entry.getName();
            if (FILE_NAMES.contains(name)) dataMap.put(name, zin.readAllBytes());
            entry = zin.getNextEntry();
        }
        zin.close();
        for (Pair<String, Function<byte[], Optional<String>>> pair : FILE_RESOLVERS) {
            String name = pair.getKey();
            Function<byte[], Optional<String>> func = pair.getValue();
            byte[] data = dataMap.get(name);
            if (data == null) continue;
            Optional<String> version = func.apply(data);
            if (version.isPresent()) {
                return version.get();
            }
        }
        throw new RuntimeException("Failed to resolve version for file " + file);
    }

    private static URL downloadUrl(FileInfo file) throws MalformedURLException {
        return new URL("https://www.cursemaven.com/curse/maven/O-" + file.projectId() + "/" + file.fileId() + "/O-" + file.projectId() + "-" + file.fileId() + ".jar");
    }

    private static Optional<String> versionFromToml(byte[] data) {
        Toml toml = new Toml().read(new StringReader(text(data)));
        List<Toml> tables = toml.getTables("mods");
        if (tables.isEmpty()) throw new IllegalStateException("No mods in mods.toml");
        if (tables.size() != 1) throw new IllegalStateException("Multiple mods in mods.toml");
        String version = tables.get(0).getString("version").strip();
        return version.startsWith("$") ? Optional.empty() : Optional.of(version);
    }

    private static Optional<String> versionFromLegacy(byte[] data) {
        JsonElement mod = Util.GSON.fromJson(text(data), JsonElement.class);
        if (mod instanceof JsonArray array) {
            if (array.size() == 1) mod = array.get(0).getAsJsonObject();
            else if (array.size() == 0) throw new IllegalStateException("No mods in mcmod.info");
            else throw new IllegalStateException("Multiple mods in mcmod.info");
        }
        if (!(mod instanceof JsonObject json)) throw new IllegalStateException("Invalid mcmod.info file");
        String version = json.get("version").getAsString().strip();
        return version.startsWith("$") ? Optional.empty() : Optional.of(version);
    }

    private static Optional<String> versionFromManifest(byte[] data) {
        return Arrays.stream(text(data)
                        .split("\n"))
                .filter(str -> MANIFEST_REGEX.matcher(str).matches())
                .map(s -> s.replaceAll("Implementation-Version:", "").strip())
                .findFirst();
    }

    private static Optional<String> versionFromModule(byte[] file) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(file)) {
            Optional<ModuleDescriptor> module = Optional.of(ModuleDescriptor.read(in));
            return module.flatMap(ModuleDescriptor::version).map(ModuleDescriptor.Version::toString);
        } catch (IOException | InvalidModuleDescriptorException e) {
            return Optional.empty();
        }
    }

    private static String text(byte[] data) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data)))) {
            return String.join("\n", reader.lines().toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
