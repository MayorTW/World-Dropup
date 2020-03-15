package tw.mayortw.dropup.util;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.*;

import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.util.EntityUtils;

import tw.mayortw.dropup.Secret;

public class GoogleDriveUtil {

    private static final String OAUTH_URL = "https://oauth2.googleapis.com/token";
    private static final String DRIVE_URL = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files";

    private String redirectUrl = "https://mayortw.github.io/World-Dropup//code.html";
    private String token;
    private String refreshToken;

    private HttpClient http = HttpClientBuilder.create().build();

    public GoogleDriveUtil() {}

    // redurectUrl = page to go after authenticated with Google
    public GoogleDriveUtil(String redirectUrl) {
        this.redirectUrl = redirectUrl;
    }

    public String getAuthUrl() {
        return "https://accounts.google.com/o/oauth2/v2/auth"
            + "?scope=https%3A//www.googleapis.com/auth/drive"
            + "&access_type=offline&response_type=code"
            + "&include_granted_scopes=true"
            + "&redirect_uri=" + redirectUrl.replaceAll(":", "%3A")
            + "&client_id=" + Secret.CLIENT_ID;
    }

    public boolean loggedIn() {
        return token != null;
    }

    // Returns refresh token that can be used to obtain real token
    public String getToken() {
        return refreshToken;
    }

    public String getLoginName() throws GoogleDriveException {
        try {
            return sendRequest(authorized("GET", DRIVE_URL + "/about").addParameter("fields", "user"))
                .getAsJsonObject("user").getAsJsonPrimitive("displayName").getAsString();
        } catch(NullPointerException e) {
            throw new GoogleDriveException(e);
        }
    }

    // Login using refresh token
    public void loginToken(String token) throws GoogleDriveException {

        this.refreshToken = token;

        JsonObject json = sendRequest(RequestBuilder.post(OAUTH_URL)
                .addParameter("client_id", Secret.CLIENT_ID)
                .addParameter("client_secret", Secret.CLIENT_SECRET)
                .addParameter("grant_type", "refresh_token")
                .addParameter("refresh_token", token));

        try {
            this.token = json.getAsJsonPrimitive("access_token").getAsString();
        } catch(NullPointerException e) {
            throw new GoogleDriveException(e);
        }
    }

    // Login using auth code obtained from auth url
    public void loginCode(String code) throws GoogleDriveException {
        JsonObject json = sendRequest(RequestBuilder.post(OAUTH_URL)
                .addParameter("client_id", Secret.CLIENT_ID)
                .addParameter("client_secret", Secret.CLIENT_SECRET)
                .addParameter("code", code)
                .addParameter("grant_type", "authorization_code")
                .addParameter("redirect_uri", redirectUrl));

        try {
            this.token = json.getAsJsonPrimitive("access_token").getAsString();
            if(json.has("refresh_token"))
                this.refreshToken = json.getAsJsonPrimitive("refresh_token").getAsString();
        } catch(NullPointerException e) {
            throw new GoogleDriveException(e);
        }
    }

    public List<String> listFileNames(String path) throws GoogleDriveException {
        List<String> names = new ArrayList<>();
        String id = findPathId(path, false);

        if(id != null) {
            JsonObject json = sendRequest(authorized("GET", DRIVE_URL + "/files")
                    .addParameter("pageSize", "1000")
                    .addParameter("fields", "files")
                    .addParameter("q", String.format("'%s' in parents and trashed != true", id)));

            try {

                JsonArray files = json.getAsJsonArray("files");
                for(int i = 0; i < files.size(); i++) {
                    names.add(files.get(i).getAsJsonObject().getAsJsonPrimitive("name").getAsString());
                }

            } catch(NullPointerException e) {
                throw new GoogleDriveException(e);
            }
        }

        return names;
    }

    // Returns filename
    public String upload(InputStream stream, String path, String name) throws GoogleDriveException {

        String parentId = findPathId(path, true);

        // Create file metadata
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        JsonArray parents = new JsonArray();
        parents.add(parentId);
        meta.add("parents", parents);

        JsonObject json = sendRequest(authorized("POST", UPLOAD_URL + "?uploadType=multipart") // Maybe try resumable in the future
                .setEntity(MultipartEntityBuilder.create()
                    .addTextBody("meta", meta.toString(), ContentType.APPLICATION_JSON)
                    .addBinaryBody("file", stream, ContentType.APPLICATION_OCTET_STREAM, name)
                    .build()));

        try {
            return json.getAsJsonPrimitive("name").getAsString();
        } catch(NullPointerException e) {
            throw new GoogleDriveException(e);
        }
    }

    public void deleteFile(String path) throws GoogleDriveException {
        String id = findPathId(path, false);
        if(id != null) {
            sendRequest(authorized("DELETE", DRIVE_URL + "/files/" + id));
        }
    }

    // Find file id from a query string
    private String queryId(String query) throws GoogleDriveException {

        JsonObject json = sendRequest(authorized("GET", DRIVE_URL + "/files")
                .addParameter("pageSize", "1000")
                .addParameter("fields", "files")
                .addParameter("q", query));

        try {
            JsonArray files = json.getAsJsonArray("files");
            if(files.size() > 0)
                return files.get(0).getAsJsonObject().getAsJsonPrimitive("id").getAsString();
        } catch(JsonSyntaxException | NullPointerException e) {}

        return null;
    }

    // Find file id of given path, creates new folders if createNew == true
    private String findPathId(String path, boolean createNew) throws GoogleDriveException {
        String id = "root";

        for(String folder : path.split("/")) {
            if(folder.equals("")) continue;
            String newId = queryId(String.format("name = '%s' and '%s' in parents and trashed != true", folder, id));

            if(newId == null) {
                if(!createNew) return null;
                // Create new folder

                // Folder metadata
                JsonObject meta = new JsonObject();
                meta.addProperty("name", folder);
                meta.addProperty("mimeType", "application/vnd.google-apps.folder");
                JsonArray parents = new JsonArray();
                parents.add(id);
                meta.add("parents", parents);

                JsonObject json = sendRequest(authorized("POST", DRIVE_URL + "/files")
                        .setEntity(new StringEntity(meta.toString(), ContentType.APPLICATION_JSON)));

                try {
                    newId = json.getAsJsonPrimitive("id").getAsString();
                } catch(NullPointerException e) {
                    throw new GoogleDriveException(e);
                }
            }

            id = newId;
        }

        return id;
    }

    private RequestBuilder authorized(String method, String url) {
        return RequestBuilder.create(method).setUri(url).addHeader("Authorization", "Bearer " + this.token);
    }

    private JsonObject sendRequest(RequestBuilder builder) throws GoogleDriveException {
        try {
            HttpEntity entity = http.execute(builder.build()).getEntity();
            if(entity == null) return null;

            String json = EntityUtils.toString(entity);
            JsonObject jobj = new JsonParser().parse(json).getAsJsonObject();
            JsonElement jerr = jobj.get("error");

            // API error
            if(jerr != null) {
                JsonPrimitive jmsg;
                if(jerr.isJsonObject())
                    jmsg = jerr.getAsJsonObject().getAsJsonPrimitive("message");
                else
                    jmsg = jerr.getAsJsonPrimitive();
                throw new GoogleDriveException(jmsg != null ? jmsg.getAsString() : null);
            }

            return jobj;

            // Other error
        } catch(JsonSyntaxException | java.io.IOException e) {
            throw new GoogleDriveException(e);
        }
    }


    public static class GoogleDriveException extends Exception {
        public GoogleDriveException(String msg) {
            super(msg);
        }
        public GoogleDriveException(Exception e) {
            super(e);
        }
    }
}
