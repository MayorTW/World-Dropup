package tw.mayortw.dropup.util;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.*;
import org.apache.http.client.*;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.apache.http.entity.mime.MultipartEntityBuilder;

import tw.mayortw.dropup.Secret;

public class GoogleDriveUtil {

    private static final String OAUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String DRIVE_URL = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files";

    private String redirectUrl = "https://mayortw.github.io/World-Dropup//code.html";
    private String token;
    private String refreshToken;

    private HttpClient http = HttpClientBuilder.create().build();

    public GoogleDriveUtil() {}

    public GoogleDriveUtil(String redirectUrl) {
        this.redirectUrl = redirectUrl;
    }

    public String getAuthUrl() {
        return OAUTH_URL
            + "?scope=https%3A//www.googleapis.com/auth/drive.file"
            + "&access_type=offline&response_type=code"
            + "&include_granted_scopes=true"
            + "&redirect_uri=" + redirectUrl.replaceAll(":", "%3A")
            + "&client_id=" + Secret.CLIENT_ID;
    }

    public boolean loggedIn() {
        return token != null;
    }

    public String getToken() {
        return refreshToken;
    }

    public String getLoginName() {
        try {
            return new JsonParser().parse(sendRequest(authorized("GET", DRIVE_URL + "/about?fields=user")))
                .getAsJsonObject().getAsJsonObject("user").getAsJsonPrimitive("displayName").getAsString();
        } catch(JsonSyntaxException | NullPointerException e) {
            return "";
        }
    }

    // Login using refresh token
    public boolean loginToken(String token) {

        this.refreshToken = token;

        String json = sendRequest(RequestBuilder.post("https://oauth2.googleapis.com/token")
            .addParameter("client_id", Secret.CLIENT_ID)
            .addParameter("client_secret", Secret.CLIENT_SECRET)
            .addParameter("grant_type", "refresh_token")
            .addParameter("refresh_token", token));

        try {
            this.token = new JsonParser().parse(json).getAsJsonObject().getAsJsonPrimitive("access_token").getAsString();
            return true;
        } catch(JsonSyntaxException | NullPointerException e) {
            return false;
        }
    }

    // Login using code
    public boolean loginCode(String code) {
        String json = sendRequest(RequestBuilder.post("https://oauth2.googleapis.com/token")
            .addParameter("client_id", Secret.CLIENT_ID)
            .addParameter("client_secret", Secret.CLIENT_SECRET)
            .addParameter("code", code)
            .addParameter("grant_type", "authorization_code")
            .addParameter("redirect_uri", redirectUrl));

        try {
            JsonObject jobj = new JsonParser().parse(json).getAsJsonObject();
            this.token = jobj.getAsJsonObject().getAsJsonPrimitive("access_token").getAsString();
            this.refreshToken = jobj.getAsJsonObject().getAsJsonPrimitive("refresh_token").getAsString();
            return true;
        } catch(JsonSyntaxException | NullPointerException e) {
            return false;
        }
    }

    public List<String> listFileNames(String path) {
        List<String> names = new ArrayList<>();
        String id = findPathId(path);

        if(id != null) {
            String json = sendRequest(authorized("GET", DRIVE_URL + "/files")
                    .addParameter("pageSize", "1000")
                    .addParameter("fields", "files")
                    .addParameter("q", String.format("'%s' in parents", id)));

            try {
                JsonArray files = new JsonParser().parse(json).getAsJsonObject().getAsJsonArray("files");
                for(int i = 0; i < files.size(); i++) {
                    names.add(files.get(i).getAsJsonObject().getAsJsonPrimitive("name").getAsString());
                }
                return names;
            } catch(JsonSyntaxException | NullPointerException e) {}
        }

        return null;
    }

    public void upload(String path, String name, InputStream stream) {

        String parentId = findPathId(path);
        if(parentId == null) {
            createFolder(path, true);
            parentId = findPathId(path);
        }

        /*
        JsonArray parents = new JsonArray();
        parents.add(parentId);
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        meta.add("parents", parents);

        String json = authorized("POST", UPLOAD_URL + "?uploadType=multipart") // Maybe try resumable in the future
            .setEntity(new MultipartEntityBuilder.create()
                    .addTextBody("meta", text.toString())
            .field("0", meta.toString(), "application/json")
            .field("1", stream, ContentType.create("application/zip"), name);

        System.out.println(json);

        try {
        } catch (NullPointerException e) {System.out.println("null"); }
        */
    }

    public void createFolder(String path, boolean recursive) {
    }

    private RequestBuilder authorized(String method, String url) {
        return RequestBuilder.create(method).setUri(url).addHeader("Authorization", "Bearer " + this.token);
    }

    private String sendRequest(RequestBuilder builder) {
        try {
            return new BasicResponseHandler().handleResponse(http.execute(builder.build()));
        } catch(java.io.IOException e) {
            return null;
        }
    }

    private String findFileId(String query) {

        String json = sendRequest(authorized("GET", DRIVE_URL + "/files")
                .addParameter("pageSize", "1000")
                .addParameter("fields", "files")
                .addParameter("q", query));

        try {
            JsonArray files = new JsonParser().parse(json).getAsJsonObject().getAsJsonArray("files");
        if(files.size() > 0)
            return files.get(0).getAsJsonObject().getAsJsonPrimitive("id").getAsString();
        } catch(JsonSyntaxException | NullPointerException e) {}

        return null;
    }

    private String findPathId(String path) {
        String id = "root";
        for(String folder : path.replaceFirst("/", "").split("/")) {
            id = findFileId(String.format("name = '%s' and '%s' in parents", folder, id));
            if(id == null) break;
        }
        return id;
    }
}
