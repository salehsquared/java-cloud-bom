/*
 * Copyright 2020 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.cloud.tools.opensource.dependencies.Bom;
import com.google.cloud.tools.opensource.dependencies.MavenRepositoryException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.artifact.Artifact;

public class TestPOM {
    private static final String basePath = "https://repo1.maven.org/maven2";
    private static final String DEFAULT_GROUP_ID = "com.google.cloud";
    private static final Map<Artifact, String> artifactToDepsVersion = new HashMap<>();

    private static final List<Artifact> successfulClientLibraries = new ArrayList<>();
    private static final List<Artifact> librariesWithoutSharedDeps = new ArrayList<>();
    private static final List<Artifact> librariesWithBadSharedDepsVersion = new ArrayList<>();

    public static void main(String[] args) throws ParseException, URISyntaxException, TemplateException, MavenRepositoryException, IOException {
        Arguments arguments = Arguments.readCommandLine(args);
        String latestSharedDependenciesVersion = getLatestSharedDeps();
        System.out.println("The latest version of google-cloud-shared-dependencies is " + latestSharedDependenciesVersion);

        List<Artifact> managedDependencies = generate(arguments.getBomFile());
        for (Artifact artifact : managedDependencies) {
            classify(artifact, latestSharedDependenciesVersion);
        }

        if (librariesWithoutSharedDeps.size() > 0 || librariesWithBadSharedDepsVersion.size() > 0) {
            System.out.println("------------------------------------------------------------------------------------");
            System.out.println("The following " + successfulClientLibraries.size() + " libraries had the latest version of google-cloud-shared-dependencies: ");
            for (Artifact artifact : successfulClientLibraries) {
                System.out.println(artifact.getArtifactId() + ":" + artifact.getVersion());
            }
            System.out.println("------------------------------------------------------------------------------------");
            System.out.println("The following " + librariesWithoutSharedDeps.size() + " libraries did not contain any version of google-cloud-shared-dependencies:");
            for (Artifact artifact : librariesWithoutSharedDeps) {
                System.out.println(artifact.getArtifactId() + ":" + artifact.getVersion());
            }
            System.out.println("------------------------------------------------------------------------------------");
            System.out.println("The following " + librariesWithBadSharedDepsVersion.size() + " libraries had outdated versions of google-cloud-shared-dependencies:");
            for (Artifact artifact : librariesWithBadSharedDepsVersion) {
                System.out.println(artifact.getArtifactId() + ":" + artifact.getVersion()
                        + ". Version found: " + artifactToDepsVersion.get(artifact));
            }
            System.out.println("------------------------------------------------------------------------------------");
            System.out.println("Total dependencies checked: " + managedDependencies.size());
            System.exit(1);
            return;
        }
        System.out.println("All found libraries were successful!");
        System.exit(0);
    }

    private static void classify(Artifact artifact, String latestSharedDependenciesVersion) {
        String sharedDepsVersion = sharedDependencyVersion(artifact);
        if(sharedDepsVersion == null) {
            
        } else {
            artifactToDepsVersion.put(artifact, sharedDepsVersion);
        }
        if (sharedDepsVersion.isEmpty()) {
            librariesWithoutSharedDeps.add(artifact);
        } else if (sharedDepsVersion.equals(latestSharedDependenciesVersion)) {
            successfulClientLibraries.add(artifact);
        } else {
            librariesWithBadSharedDepsVersion.add(artifact);
        }
    }

    public static String getLatestSharedDeps() {
        return getLatestVersion("com.google.cloud", "google-cloud-shared-dependencies");
    }

    @VisibleForTesting
    static List<Artifact> generate(Path bomFile) throws IOException, TemplateException, URISyntaxException, MavenRepositoryException {
        Preconditions.checkArgument(Files.isRegularFile(bomFile, new LinkOption[0]), "The input BOM %s is not a regular file", bomFile);
        Preconditions.checkArgument(Files.isReadable(bomFile), "The input BOM %s is not readable", bomFile);
        return generate(Bom.readBom(bomFile));
    }

    private static List<Artifact> generate(Bom bom) throws IOException, TemplateException, URISyntaxException {
        List<Artifact> managedDependencies = new ArrayList(bom.getManagedDependencies());
        managedDependencies.removeIf((a) -> {
            return a.getArtifactId().contains("google-cloud-core") || a.getArtifactId().contains("bigtable-emulator") || !"com.google.cloud".equals(a.getGroupId());
        });
        return managedDependencies;
    }

    private static String sharedDependencyVersion(Artifact artifact) {
        String groupPath = artifact.getGroupId().replace('.', '/');
        String pomPath = getPomFileURL(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        String parentPath = basePath + groupPath + "/" + artifact.getArtifactId() + "-parent/" + artifact.getVersion() + "/" + artifact.getArtifactId() + "-parent-" + artifact.getVersion() + ".pom";
        String depsBomPath = basePath + groupPath + "/" + artifact.getArtifactId() + "-deps-bom/" + artifact.getVersion() + "/" + artifact.getArtifactId() + "-deps-bom-" + artifact.getVersion() + ".pom";
        String version = getSharedDepsVersionFromURL(parentPath);
        if (version != null) {
            return version;
        } else {
            version = getSharedDepsVersionFromURL(pomPath);
            if (version != null) {
                return version;
            } else {
                version = getSharedDepsVersionFromURL(depsBomPath);
                if (version != null) {
                   return version;
                }
            }
        }
        return "";
    }

    private static String getSharedDepsVersionFromURL(String pomURL) {
        File file = new File("pomFile.xml");

        try {
            URL url = new URL(pomURL);
            FileUtils.copyURLToFile(url, file);
            MavenXpp3Reader read = new MavenXpp3Reader();
            Model model = read.read(new FileReader(file));
            if (model.getDependencyManagement() == null) {
                return null;
            }

            Iterator var5 = model.getDependencyManagement().getDependencies().iterator();

            while (var5.hasNext()) {
                Dependency dep = (Dependency) var5.next();
                if ("com.google.cloud".equals(dep.getGroupId()) && "google-cloud-shared-dependencies".equals(dep.getArtifactId())) {
                    return dep.getVersion();
                }
            }
        } catch (IOException | XmlPullParserException var7) {
        }

        file.deleteOnExit();
        return null;
    }

    private static String getLatestVersion(String groupId, String artifactId) {
        String pomPath = getMetaDataURL(groupId, artifactId);

        try {
            URL url = new URL(pomPath);
            URLConnection conn = url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            Scanner s = new Scanner(conn.getInputStream());

            while (s.hasNextLine()) {
                String string = s.nextLine();
                if (string.contains("<latest>")) {
                    String version = string.split(">")[1].split("<")[0];
                    return version;
                }
            }
        } catch (IOException var8) {
            var8.printStackTrace();
        }

        return null;
    }

    private static String getLatestVersion(Artifact artifact) {
        return getLatestVersion(artifact.getGroupId(), artifact.getArtifactId());
    }

    private static String getMetadataURL(Artifact artifact) {
        return getMetaDataURL(artifact.getGroupId(), artifact.getArtifactId());
    }

    private static String getMetaDataURL(String groupId, String artifactId) {
        String groupPath = groupId.replace('.', '/');
        return basePath + groupPath + "/" + artifactId + "/maven-metadata.xml";
    }

    private static String getPomFileURL(String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', '/');
        return basePath + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";
    }
}
