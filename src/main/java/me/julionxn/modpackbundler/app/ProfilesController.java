package me.julionxn.modpackbundler.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import me.julionxn.modpackbundler.app.profile.ProfileData;
import me.julionxn.modpackbundler.app.profile.ProfileDataController;
import me.julionxn.modpackbundler.app.profile.ProfileItem;
import me.julionxn.modpackbundler.models.LoaderInfo;
import me.julionxn.modpackbundler.models.LoaderType;
import me.julionxn.modpackbundler.models.Profile;
import me.julionxn.modpackbundler.models.Project;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ProfilesController {

    @FXML private AnchorPane profilesContainer;
    private Project project;
    private final List<ProfileItem> profileItems = new ArrayList<>();
    private ProfileItem currentProfile;

    public void setProject(Project project) {
        this.project = project;
        reloadProfiles();
    }

    public void setCurrentProfile(ProfileItem profile) {
        this.currentProfile = profile;
    }

    public void addProfile(){
        openProjectDataView(null);
    }

    public void addProfile(ProfileData profileData){
        String name = profileData.name();
        String version = profileData.version();
        LoaderType loaderType = profileData.loaderType();
        String loaderVersion = profileData.loaderVersion();
        Profile profile = project.addProfile(name);
        profile.setVersion(version);
        profile.setLoaderInfo(new LoaderInfo(loaderType, loaderVersion));
        profile.saveManifest();
    }

    public void editProfile(){
        openProjectDataView(currentProfile.getProfile());
    }

    public void editProfile(Profile profile, ProfileData profileData){
        String name = profileData.name();
        String version = profileData.version();
        LoaderType loaderType = profileData.loaderType();
        String loaderVersion = profileData.loaderVersion();
        profile.rename(name);
        profile.setVersion(version);
        profile.setLoaderInfo(new LoaderInfo(loaderType, loaderVersion));
        profile.saveManifest();
    }

    private void openProjectDataView(@Nullable Profile profileModified){
        String title = profileModified == null ? "Add Profile" : "Edit Profile";
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/me/julionxn/modpackbundler/data-views/profile-data-view.fxml"));
            Parent newView = loader.load();
            ProfileDataController controller = loader.getController();
            controller.init(this, profileModified);
            Stage newWindow = new Stage();
            newWindow.setTitle(title);
            newWindow.setScene(new Scene(newView));
            newWindow.showAndWait();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void openProfile(){
        File folder = new File(String.valueOf(currentProfile.getProfile().path));
        if (folder.exists() && folder.isDirectory()) {
            try {
                Desktop.getDesktop().open(folder);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            System.out.println("Folder does not exist or is not a directory.");
        }
    }

    public void removeProfile(){
        Profile profile = currentProfile.getProfile();
        boolean success1 = project.removeProfile(profile.name);
        boolean success2 = profile.remove();
        if (success1 && success2) {
            reloadProfiles();
        }
    }

    public void reloadProfiles(){
        List<Profile> profiles = project.getProfiles();
        profileItems.clear();
        for (Profile profile : profiles) {
            ProfileItem item = new ProfileItem(this, profile);
            profileItems.add(item);
        }
        cleanAndShowItems();
    }

    private void cleanAndShowItems(){
        profilesContainer.getChildren().clear();
        int padding = 20;
        int itemWidth = 60;
        int itemHeight = 90;
        int itemsPerRow = (int) ((profilesContainer.getPrefWidth() - padding) / (itemWidth + padding));
        for (int i = 0; i < profileItems.size(); i++) {
            StackPane item = profileItems.get(i).getStackPane();
            int row = i / itemsPerRow;
            int column = i % itemsPerRow;
            double x = column * (itemWidth + padding) + padding;
            double y = row * (itemHeight + padding) + padding;
            item.setLayoutX(x);
            item.setLayoutY(y);
            profilesContainer.getChildren().add(item);
        }
    }

    public void bundle() {
        JsonObject jsonOutput = new JsonObject();
        File rootDirectory = project.path.toFile();
        if (rootDirectory.isDirectory()) {
            File[] mainDirs = rootDirectory.listFiles(File::isDirectory);
            if (mainDirs != null) {
                for (File mainDir : mainDirs) {
                    JsonObject dirNode = new JsonObject();
                    File manifestFile = new File(mainDir, "manifest.json");
                    if (manifestFile.exists() && manifestFile.isFile()) {
                        String relativeManifestPath = getRelativePath(manifestFile, rootDirectory);
                        dirNode.addProperty("manifest", relativeManifestPath);
                    } else {
                        dirNode.addProperty("manifest", "not-found");
                    }
                    JsonObject filesNode = new JsonObject();
                    traverseDirectory(mainDir, filesNode, mainDir.getName());
                    dirNode.add("files", filesNode);
                    jsonOutput.add(mainDir.getName(), dirNode);
                }
            }
        }
        File manifestFile = new File(rootDirectory, "manifest.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter fileWriter = new FileWriter(manifestFile)) {
            gson.toJson(jsonOutput, fileWriter);
            System.out.println("Successfully wrote manifest.json to the root directory.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        zipDirectory(rootDirectory);
    }

    private void zipDirectory(File rootDirectory) {
        File zipFile = new File(rootDirectory, project.name + ".zip");
        if (zipFile.exists()) {
            System.out.println("Existing bundle.zip found, overwriting...");
            zipFile.delete();
        }
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile));
             Stream<Path> stream = Files.walk(rootDirectory.toPath())) {
            stream.filter(path -> !path.toString().endsWith(".zip"))
                    .forEach(path -> {
                        try {
                            Path relativePath = rootDirectory.toPath().relativize(path);
                            if (Files.isDirectory(path)) {
                                zipOut.putNextEntry(new ZipEntry(relativePath + "/"));
                            } else {
                                zipOut.putNextEntry(new ZipEntry(relativePath.toString()));
                                Files.copy(path, zipOut);
                            }
                            zipOut.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            System.out.println("Successfully zipped the directory into bundle.zip");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void traverseDirectory(File rootDir, JsonObject parentNode, String parentPath) {
        Deque<TraversalNode> stack = new ArrayDeque<>();
        stack.push(new TraversalNode(rootDir, parentNode, parentPath));
        while (!stack.isEmpty()) {
            TraversalNode currentNode = stack.pop();
            File directory = currentNode.directory;
            JsonObject parentNodeForDir = currentNode.parentNode;
            String currentPath = currentNode.currentPath;
            File[] contents = directory.listFiles();
            if (contents != null) {
                for (File file : contents) {
                    if (file.getName().equals("manifest.json")) continue;
                    String relativePath = currentPath + "/" + file.getName();
                    if (file.isDirectory()) {
                        JsonObject dirNode = new JsonObject();
                        dirNode.addProperty("type", "directory");
                        JsonObject nestedFilesNode = new JsonObject();
                        stack.push(new TraversalNode(file, nestedFilesNode, relativePath));
                        dirNode.add("files", nestedFilesNode);
                        parentNodeForDir.add(relativePath, dirNode);
                    } else {
                        JsonObject fileNode = new JsonObject();
                        fileNode.addProperty("type", "file");
                        String hash = getSHA1Hash(file);
                        fileNode.addProperty("hash", hash);
                        fileNode.addProperty("size", file.length());
                        parentNodeForDir.add(relativePath, fileNode);
                    }
                }
            }
        }
    }

    protected String getSHA1Hash(File file) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    messageDigest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = messageDigest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private String getRelativePath(File file, File rootDirectory) {
        String rootPath = rootDirectory.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        return filePath.startsWith(rootPath) ? filePath.substring(rootPath.length() + 1).replace("\\", "/") : filePath;
    }

    private record TraversalNode(File directory, JsonObject parentNode, String currentPath) {
    }

}
