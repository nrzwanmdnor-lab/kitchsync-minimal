package com.kitchsync.app.ingestion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Google ML Kit Text Recognition Engine for camera photos, scanned dockets, and screenshots.
 */
public class MlKitIngestion {
    private static final String TAG = "MlKitIngestion";

    /**
     * Extracts text from a Bitmap using Google ML Kit on-device Text Recognition.
     */
    public static String recognizeBitmap(Bitmap bitmap) throws Exception {
        if (bitmap == null) {
            throw new IllegalArgumentException("Bitmap cannot be null");
        }

        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

        Task<Text> task = recognizer.process(inputImage);
        Text visionText = Tasks.await(task, 30, TimeUnit.SECONDS);

        if (visionText == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                if (lineText != null && !lineText.trim().isEmpty()) {
                    sb.append(lineText.trim()).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * Decodes a Base64 encoded image string (with or without data URI prefix) and performs ML Kit Text Recognition.
     */
    public static String recognizeBase64(String base64Data) throws Exception {
        if (base64Data == null || base64Data.trim().isEmpty()) {
            throw new IllegalArgumentException("Base64 image data cannot be empty");
        }

        int commaIdx = base64Data.indexOf(',');
        if (commaIdx != -1) {
            base64Data = base64Data.substring(commaIdx + 1);
        }

        byte[] imageBytes = Base64.decode(base64Data, Base64.DEFAULT);
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (bitmap == null) {
            throw new IllegalArgumentException("Failed to decode image from provided data");
        }

        try {
            return recognizeBitmap(bitmap);
        } finally {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    /**
     * Performs ML Kit Text Recognition from an InputStream.
     */
    public static String recognizeInputStream(InputStream inputStream) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
        if (bitmap == null) {
            throw new IllegalArgumentException("Failed to decode image from stream");
        }
        try {
            return recognizeBitmap(bitmap);
        } finally {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }
}
