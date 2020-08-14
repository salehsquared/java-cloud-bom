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
    private static final Map<Artifact, String> artifactToDepsVersion = new HashMap<>();

    /**
     * These are the only four possibilities for any given client libraries.
     */
    private static final List<Artifact> successfulClientLibraries = new ArrayList<>();
    private static final List<Artifact> librariesWithoutSharedDeps = new ArrayList<>();
    private static final List<Artifact> librariesWithBadSharedDepsVersion = new ArrayList<>();
    private static final List<Artifact> unfindableClientLibraries = new ArrayList<>();

    private static final List[] librariesClassified = new List[]{
            successfulClientLibraries, librariesWithoutSharedDeps,
            librariesWithBadSharedDepsVersion, unfindableClientLibraries
    };

    private static final String[] outputStatements = {
            "SUCCESS - The following %s libraries had the latest version of google-cloud-shared-dependencies: ",
            "FAIL - The following %s libraries did not contain any version of google-cloud-shared-dependencies: ",
            "FAIL - The following %s libraries had outdated versions of google-cloud-shared-dependencies: ",
            "FAIL - The following %s libraries had unfindable POM files: "
    };

    public static void main(String[] args) throws ParseException, URISyntaxException, TemplateException, MavenRepositoryException, IOException {
        Arguments arguments = Arguments.readCommandLine(args);
        String latestSharedDependenciesVersion = getLatestSharedDeps();
        System.out.println("The latest version of google-cloud-shared-dependencies is " + latestSharedDependenciesVersion);

        List<Artifact> managedDependencies = generate(arguments.getBomFile());
        for (Artifact artifact : managedDependencies) {
            classify(artifact, latestSharedDependenciesVersion);
        }

        for (int i = 0; i < librariesClassified.length; i++) {
            List<Artifact> clientLibraryList = (List<Artifact>) librariesClassified[i];
            if (clientLibraryList.size() <= 0) {
                continue;
            }
            System.out.println("------------------------------------------------------------------------------------");
            String output = outputStatements[i];
            System.out.println(String.format(output, clientLibraryList.size()));
            for (Artifact artifact : clientLibraryList) {
                System.out.print(artifact.getArtifactId() + ":" + artifact.getVersion());
                String foundDepsVersion = artifactToDepsVersion.get(artifact);
                if (foundDepsVersion == null || foundDepsVersion.isEmpty()) {
                    foundDepsVersion = "";
                } else {
                    foundDepsVersion = " Version Found: " + foundDepsVersion;
                }
                System.out.println(foundDepsVersion);
            }
        }

        if (managedDependencies.size() > successfulClientLibraries.size()) {
            System.out.println("Total dependencies checked: " + managedDependencies.size());
            System.exit(1);
            return;
        }
        System.out.println("Total dependencies checked: " + managedDependencies.size());
        System.out.println("All found libraries were successful!");
        System.exit(0);
    }

    private static void classify(Artifact artifact, String latestSharedDependenciesVersion) {
        String sharedDepsVersion = sharedDependencyVersion(true, artifact);
        if (sharedDepsVersion == null) {
            unfindableClientLibraries.add(artifact);
            return;
        }
        artifactToDepsVersion.put(artifact, sharedDepsVersion);
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
    static List<Artifact> generate(Path bomFile) throws MavenRepositoryException {
        Preconditions.checkArgument(Files.isRegularFile(bomFile, new LinkOption[0]), "The input BOM %s is not a regular file", bomFile);
        Preconditions.checkArgument(Files.isReadable(bomFile), "The input BOM %s is not readable", bomFile);
        return generate(Bom.readBom(bomFile));
    }

    private static List<Artifact> generate(Bom bom) {
        List<Artifact> managedDependencies = new ArrayList(bom.getManagedDependencies());
        managedDependencies.removeIf((a) -> {
            return a.getArtifactId().contains("google-cloud-core")
                    || a.getArtifactId().contains("bigtable-emulator")
                    || !"com.google.cloud".equals(a.getGroupId());
        });
        return managedDependencies;
    }

    private static String sharedDependencyVersion(boolean useParentPom, Artifact artifact) {
        String groupId = artifact.getGroupId();
        String artifactId = artifact.getArtifactId();
        String version = getLatestVersion(groupId, artifactId);
        String pomURL = useParentPom ? getParentPomFileURL(groupId, artifactId, version) :
            getPomFileURL(groupId, artifactId, version);
        String pomLocation =  "/pom.xml";
        File file = new File("pomFile.xml");
        String repoURL = null;
        try {
            URL url = new URL(pomURL);
            FileUtils.copyURLToFile(url, file);
            MavenXpp3Reader read = new MavenXpp3Reader();
            Model model = read.read(new FileReader(file));
            if (model.getScm() == null || model.getScm().getUrl() == null) {
                System.out.println("Unable to find scm section for: " + artifact);
                if(model.getDependencyManagement() == null) {
                    return "";
                }
                Iterator<Dependency> iter = model.getDependencyManagement().getDependencies().iterator();
                while (iter.hasNext()) {
                    Dependency dep = iter.next();
                    if ("com.google.cloud".equals(dep.getGroupId()) && "google-cloud-shared-dependencies".equals(dep.getArtifactId())) {
                        return dep.getVersion();
                    }
                }
                if(useParentPom) {
                    return sharedDependencyVersion(false, artifact);
                }
                return "";
            }

            repoURL = model.getScm().getUrl();
            String gitPomURL = repoURL.replace("github.com", "raw.githubusercontent.com");
            gitPomURL += ("/v" + version + pomLocation);;

            url = new URL(gitPomURL);
            FileUtils.copyURLToFile(url, file);

            read = new MavenXpp3Reader();
            model = read.read(new FileReader(file));

            if(model.getDependencyManagement() == null) {
                if(useParentPom) {
                    return sharedDependencyVersion(false, artifact);
                }
                return "";
            }

            Iterator<Dependency> iter = model.getDependencyManagement().getDependencies().iterator();
            while (iter.hasNext()) {
                Dependency dep = iter.next();
                if ("com.google.cloud".equals(dep.getGroupId()) && "google-cloud-shared-dependencies".equals(dep.getArtifactId())) {
                    return dep.getVersion();
                }
            }
        } catch (XmlPullParserException | IOException ignored) {
            System.out.println("Artifact: " + artifactId + ". Original repo URL: " + repoURL);
            System.out.println("Secondary Repo URL: " + pomURL);
        }
        if(useParentPom) {
            return sharedDependencyVersion(false, artifact);
        }
        return null;
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

            Iterator<Dependency> iter = model.getDependencyManagement().getDependencies().iterator();

            while (iter.hasNext()) {
                Dependency dep = iter.next();
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

    private static String getMetaDataURL(String groupId, String artifactId) {
        String groupPath = groupId.replace('.', '/');
        return basePath + "/" + groupPath
                + "/" + artifactId
                + "/maven-metadata.xml";
    }

    private static String getParentPomFileURL(String groupId, String artifactId, String version) {
        artifactId += "-parent";
        String groupPath = groupId.replace('.', '/');
        return basePath + "/" + groupPath
                + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + ".pom";
    }

    private static String getPomFileURL(String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', '/');
        return basePath + "/" + groupPath
            + "/" + artifactId
            + "/" + version
            + "/" + artifactId + "-" + version + ".pom";
    }
}