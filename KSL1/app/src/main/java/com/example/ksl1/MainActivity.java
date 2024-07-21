package com.example.ksl1;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.FirebaseApp;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.google.firebase.ml.modeldownloader.CustomModel;
import com.google.firebase.ml.modeldownloader.CustomModelDownloadConditions;
import com.google.firebase.ml.modeldownloader.DownloadType;
import com.google.firebase.ml.modeldownloader.FirebaseModelDownloader;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_IMAGE_CAPTURE = 1;
    private static final int REQUEST_IMAGE_SELECT = 2;
    private Bitmap selectedImage;
    private Interpreter tflite;
    private FirebaseStorage storage;
    private StorageReference storageRef;
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Firebase
        FirebaseApp.initializeApp(this);
        storage = FirebaseStorage.getInstance();
        storageRef = storage.getReference();

        Log.d(TAG, "Firebase initialized.");

        Button buttonSelect = findViewById(R.id.buttonSelect);
        Button buttonCapture = findViewById(R.id.buttonCapture);
        Button buttonPredict = findViewById(R.id.buttonPredict);
        Button buttonUpload = findViewById(R.id.buttonUpload);
        Button buttonDownload = findViewById(R.id.buttonDownload);

        buttonSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectImageFromGallery();
            }
        });

        buttonCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureImageFromCamera();
            }
        });

        buttonPredict.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                predictSignLanguage();
            }
        });

        buttonUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedImage != null) {
                    uploadImage();
                } else {
                    Toast.makeText(MainActivity.this, "Please select or capture an image first", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buttonDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadImage();
            }
        });

        // Request camera and storage permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        }

        // Download the model from Firebase
        CustomModelDownloadConditions conditions = new CustomModelDownloadConditions.Builder()
                .requireWifi()
                .build();
        FirebaseModelDownloader.getInstance()
                .getModel("model", DownloadType.LOCAL_MODEL, conditions)
                .addOnSuccessListener(new OnSuccessListener<CustomModel>() {
                    @Override
                    public void onSuccess(CustomModel model) {
                        Log.d(TAG, "Model downloaded successfully!");
                        // Use the downloaded model to create an interpreter
                        File modelFile = model.getFile();
                        if (modelFile != null) {
                            try {
                                tflite = new Interpreter(modelFile);
                                Log.d(TAG, "Interpreter initialized.");
                            } catch (Exception e) {
                                Log.e(TAG, "Error initializing interpreter: " + e.getMessage());
                            }
                        } else {
                            Log.e(TAG, "Model file is null");
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, "Model download failed: " + e.getMessage());
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_IMAGE_CAPTURE && data != null) {
                Bundle extras = data.getExtras();
                selectedImage = (Bitmap) Objects.requireNonNull(extras).get("data");
            } else if (requestCode == REQUEST_IMAGE_SELECT && data != null) {
                Uri imageUri = data.getData();
                try {
                    selectedImage = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void selectImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, REQUEST_IMAGE_SELECT);
    }

    private void captureImageFromCamera() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        }
    }

    private void predictSignLanguage() {
        if (selectedImage != null) {
            if (tflite == null) {
                Log.e(TAG, "Model is not downloaded yet");
                Toast.makeText(this, "Model is not downloaded yet", Toast.LENGTH_SHORT).show();
                return;
            }

            // Preprocess the image: Resize, normalize, and convert to ByteBuffer
            Bitmap resizedImage = Bitmap.createScaledBitmap(selectedImage, 96, 96, true);
            ByteBuffer byteBuffer = convertBitmapToByteBuffer(resizedImage);

            // Creates inputs for reference.
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 96, 96, 3}, DataType.FLOAT32);
            inputFeature0.loadBuffer(byteBuffer);

            // Runs model inference and gets result.
            TensorBuffer outputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 1}, DataType.FLOAT32);

            try {
                tflite.run(inputFeature0.getBuffer(), outputFeature0.getBuffer().rewind());
                // Get the predicted label
                float predictedLabel = outputFeature0.getFloatArray()[0];
                // Display the result
                TextView textViewResult = findViewById(R.id.textViewResult);
                textViewResult.setText("Result: " + getLabelFromPrediction(predictedLabel));
            } catch (Exception e) {
                Log.e(TAG, "Error during model inference: " + e.getMessage());
            }
        } else {
            Toast.makeText(this, "Please select or capture an image first", Toast.LENGTH_SHORT).show();
        }
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * 96 * 96 * 3);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[96 * 96];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < 96; ++i) {
            for (int j = 0; j < 96; ++j) {
                final int val = intValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                byteBuffer.putFloat((val & 0xFF) / 255.0f);
            }
        }
        return byteBuffer;
    }

    private float getLabelFromPrediction(float prediction) {
        // Debug log
        Log.d(TAG, "Predicted raw value: " + prediction);
        return prediction;
        // Assuming labels are {0: 'A', 1: 'D', 2: 'E'}
        /*if (prediction < 0.5) {
            return "A";
        } else if (prediction < 1.5) {
            return "D";
        } else {
            return "E";
        }*/
    }

    private void uploadImage() {
        // Convert Bitmap to Uri
        Uri fileUri = getImageUri(selectedImage);

        if (fileUri != null) {
            StorageReference fileRef = storageRef.child("images/" + fileUri.getLastPathSegment());

            fileRef.putFile(fileUri)
                    .addOnSuccessListener(taskSnapshot -> {
                        // Handle success
                        Log.d(TAG, "Upload successful: " + taskSnapshot.getMetadata().getName());
                        Toast.makeText(MainActivity.this, "Upload successful", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(exception -> {
                        // Handle failure
                        Log.e(TAG, "Upload failed: " + exception.getMessage());
                        Toast.makeText(MainActivity.this, "Upload failed", Toast.LENGTH_SHORT).show();
                    });
        } else {
            Toast.makeText(MainActivity.this, "File URI is null", Toast.LENGTH_SHORT).show();
        }
    }

    private Uri getImageUri(Bitmap bitmap) {
        // Convert bitmap to file and return Uri
        File file = new File(getExternalFilesDir(null), "temp_image.jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return Uri.fromFile(file);
    }

    private void downloadImage() {
        StorageReference fileRef = storageRef.child("images/your_image.jpg");
        File localFile = new File(getFilesDir(), "downloaded_image.jpg");

        fileRef.getFile(localFile)
                .addOnSuccessListener(taskSnapshot -> {
                    // Handle success
                    Bitmap bitmap = BitmapFactory.decodeFile(localFile.getAbsolutePath());
                    ImageView imageView = findViewById(R.id.imageView);
                    imageView.setImageBitmap(bitmap);
                    Log.d(TAG, "Download successful.");
                    Toast.makeText(MainActivity.this, "Download successful", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(exception -> {
                    // Handle failure
                    Log.e(TAG, "Download failed: " + exception.getMessage());
                    Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show();
                });
    }
}