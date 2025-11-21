package org.example.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.json.JSONArray;

public class tgBot extends TelegramLongPollingBot {


    @Override
    public String getBotUsername() {
        return "@optimusZeromaxBot";
    }

    @Override
    public String getBotToken() {
        return "8242382523:AAGk7wCPp-BC-sj92yXcCH4dE-L_oz0wlvQ";
    }


    private static final String DITAT_USERNAME = "Aziz";
    private static final String DITAT_PASSWORD = "Aziz123";
    private static final String DITAT_ACCOUNTID = "martianexpress";
    private static final int REPAIRS_FOLDER_KEY = 73;


    private static class PendingUpload {

        String fileId;
        String fileName;

        public PendingUpload(String fileId, String fileName) {
            this.fileId = fileId;
            this.fileName = fileName;
        }
    }
    private final Map<Long, PendingUpload> pendingUploads = new HashMap<>();

    @Override
    public void onUpdateReceived(Update update) {

        if (!update.hasMessage()) return;
        Message msg = update.getMessage();

        if (msg.hasDocument()) {
            Document doc = msg.getDocument();
            if (doc != null && isSupportedMimeType(doc.getMimeType())) {
                String caption = msg.getCaption();
                if (caption != null && !caption.trim().isEmpty()) {
                    File downloaded = downloadFile(doc.getFileId(), doc.getFileName());
                    processAndUpload(msg.getChatId(), caption.trim(), downloaded);
                } else {
                    pendingUploads.put(msg.getChatId(), new PendingUpload(doc.getFileId(), doc.getFileName()));
                    sendMessage(msg.getChatId(), "❗ Please provide a caption for this file.");
                }
                return;
            }
        }

        if (msg.hasPhoto()) {
            List<PhotoSize> photos = msg.getPhoto();
            if (photos != null && !photos.isEmpty()) {
                PhotoSize largest = photos.get(photos.size() - 1);
                String caption = msg.getCaption();
                if (caption != null && !caption.trim().isEmpty()) {
                    File downloaded = downloadFile(largest.getFileId(), "photo.jpg");
                    processAndUpload(msg.getChatId(), caption.trim(), downloaded);
                } else {
                    pendingUploads.put(msg.getChatId(), new PendingUpload(largest.getFileId(), "photo.jpg"));
                    sendMessage(msg.getChatId(), "❗ Please provide a caption for this photo.");
                }
                return;
            }
        }

        if (msg.hasText() && msg.isReply()) {
            Message replied = msg.getReplyToMessage();
            if (replied != null && (replied.hasDocument() || replied.hasPhoto())) {
                Long chatId = msg.getChatId();
                String caption = msg.getText().trim();

                if (pendingUploads.containsKey(chatId)) {
                    PendingUpload pending = pendingUploads.remove(chatId);
                    File downloaded = downloadFile(pending.fileId, pending.fileName);
                    if (downloaded != null) {
                        processAndUpload(chatId, caption, downloaded);
                    } else {
                        sendMessage(chatId, "❌ Could not download file from Telegram.");
                    }
                    return;
                }
            }
        }
        System.out.println(" Unsupported message type — ignoring.");
    }

    private String getExtension(File file) {
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex != -1) {
            return name.substring(dotIndex);
        }
        return "";
    }

    private void processAndUpload(Long chatId, String caption, File downloaded) {

        ParsedCaption parsedCaption;
        try {
            parsedCaption = parseCaption(caption);
        } catch (Exception e) {
            sendMessage(chatId, "❌ Caption invalid: " + e.getMessage()
                    + "\n\nUse format: Truck 0028 $200 TireRepair");
            return;
        }


        String token = loginToDitat(DITAT_USERNAME, DITAT_PASSWORD, DITAT_ACCOUNTID);
        if (token == null) {
            sendMessage(chatId, "❌ Could not login to Ditat API.");
            return;
        }


        String truckKey = findTruckKeyByUnitNumber(parsedCaption.getUnitNumber(), token, DITAT_ACCOUNTID);
        if (truckKey == null) {
            sendMessage(chatId, "❌ Could not find truck with unit number: " + parsedCaption.getUnitNumber());
            return;
        }

        // Upload
        boolean ok = uploadToDitat(truckKey, caption, downloaded, token);
        if (ok) {
            sendMessage(chatId, "✅ Uploaded to Ditat (truck " + parsedCaption.getUnitNumber() + " → Repairs).");
        } else {
            sendMessage(chatId, "❌ Upload to Ditat failed. Check logs.");
        }
    }


    private boolean isSupportedMimeType(String mimeType) {
        return mimeType != null && (
                mimeType.equals("application/pdf") )
        ;
    }


    public ParsedCaption parseCaption(String caption) throws Exception {
        if (caption == null || caption.isEmpty()) {
            throw new Exception("Caption is empty.");
        }

        String[] parts = caption.trim().split("\\s+");
        if (parts.length != 4) {
            throw new Exception("Caption must have 4 parts: VEHICLE_TYPE UNIT_NUMBER REPAIR_COST REPAIR_TYPE");
        }

        String vehicleType = parts[0];
        String vehicleUnitType = parts[1];
        String repairCostStr = parts[2];
        String repairType = parts[3];


        if (!repairCostStr.startsWith("$")) {
            throw new Exception("Repair cost must start with '$' (e.g. $200).");
        }


        String vehicleNo = vehicleUnitType;


        return new ParsedCaption(vehicleType, vehicleNo, repairCostStr, repairType);
    }




    private File downloadFile(String fileId, String desiredFileName) {
        try {
            File folder = new File("downloads");
            if (!folder.exists()) folder.mkdir();

            File output = new File(folder, desiredFileName);

            GetFile getFile = new GetFile(fileId);
            org.telegram.telegrambots.meta.api.objects.File tgFile = execute(getFile);

            downloadFile(tgFile, output);

            return output;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void sendMessage(Long chatId, String text) {
        try {
            execute(new SendMessage(chatId.toString(), text));
        } catch (Exception ignored) {
        }
    }


    public String loginToDitat(String username, String password, String accountId) {
        try {
            URL url = new URL("https://tmsapi01.ditat.net/api/tms/auth/login");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            conn.setRequestProperty("ditat-account-id", accountId);
            conn.setRequestProperty("ditat-application-role", "Login to TMS");

            String basicAuth = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

            conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            conn.setRequestProperty("Content-Type", "application/json");


            try (OutputStream os = conn.getOutputStream()) {
                os.write("{}".getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            System.out.println("Login code = " + code);

            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String token = reader.readLine();
                reader.close();
                return token;
            } else {
                BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                String line = err.readLine();
                System.out.println("Login error: " + line);
                return null;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }



    public String findTruckKeyByUnitNumber(String unitNumber, String token, String accountId) {
        try {
            URL url = new URL("https://tmsapi01.ditat.net/api/tms/lookup/trucks");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);


            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("ditat-account-id", accountId);
            conn.setRequestProperty("ditat-application-role", "TMS");
            conn.setRequestProperty("Authorization", "ditat-token " + token);


            String body = """
                    {
                      "filters": [
                        {
                          "columnName": "truckId",
                          "filterFormalValue": "%s",
                          "filterType": 5
                        }
                      ]
                    }
                    """.formatted(unitNumber);

            System.out.println("Truck lookup payload:\n" + body);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            System.out.println("Truck lookup response = " + code);

            if (code != 200) {
                try (BufferedReader errReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    StringBuilder errSb = new StringBuilder();
                    String errLine;
                    while ((errLine = errReader.readLine()) != null) {
                        errSb.append(errLine);
                    }
                    System.err.println("Error response: " + errSb);
                }
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            String json = sb.toString();
            // System.out.println("Truck lookup JSON:\n" + json);


            JSONObject obj = new JSONObject(json);
            JSONObject dataWrapper = obj.optJSONObject("data");
            if (dataWrapper == null) return null;

            JSONArray trucks = dataWrapper.optJSONArray("data");
            if (trucks == null || trucks.length() == 0) return null;

            // Match truckId to unitNumber
            for (int i = 0; i < trucks.length(); i++) {
                JSONObject truck = trucks.getJSONObject(i);
                String tid = truck.optString("truckId", "");
                if (tid.equals(unitNumber)) {
                    String truckKey = truck.optString("truckKey", null);
                    System.out.println("Matched truckId = " + tid + " → truckKey = " + truckKey);
                    return truckKey;
                }
            }

            System.out.println("No matching truckId found for unit: " + unitNumber);
            return null;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    public boolean uploadToDitat(String truckId, String caption, File file, String token) {
        try {
            // Read file bytes and encode base64
            byte[] bytes = Files.readAllBytes(file.toPath());
            String base64 = Base64.getEncoder().encodeToString(bytes);

            String safeCaption = caption.replaceAll("[^a-zA-Z0-9\\s]", "").replace(" ", "_");
            String extension = getExtension(file);
            String fileName = safeCaption + extension;

            String json = """
                    {
                        documentFolderKey: "%s",
                        fileNameWithExtension: "%s",
                        fileDataInBase64: "%s"
                    }
                    """.formatted(REPAIRS_FOLDER_KEY, fileName, base64);


            String urlStr = "https://tmsapi01.ditat.net/api/tms/data/truck/" + truckId + "/document";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);


            conn.setRequestProperty("ditat-account-id", DITAT_ACCOUNTID);
            conn.setRequestProperty("ditat-application-role", "TMS");
            conn.setRequestProperty("Authorization", "ditat-token " + token);
            conn.setRequestProperty("Content-Type", "application/json");


            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            System.out.println("UPLOAD RESPONSE = " + responseCode);


            InputStream responseStream = (responseCode == 200)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (responseStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    System.out.println("UPLOAD RESPONSE BODY = " + sb);
                }
            }

            return responseCode == 200;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

}
