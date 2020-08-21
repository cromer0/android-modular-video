package com.siempre.siemprevideo;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {
    FirebaseFirestore db;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        db = FirebaseFirestore.getInstance();

        final SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);
        final SharedPreferences.Editor prefsEdit = prefs.edit();

        if (!prefs.getString("username", "").equals("")) {
            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            startActivity(intent);
        }

        final EditText nameText = findViewById(R.id.nameText);
        final EditText friendText = findViewById(R.id.friendText);
        Button loginButton = findViewById(R.id.loginButton);
        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String name = nameText.getText().toString().trim();
                final String friend = friendText.getText().toString().trim();
                db.collection("users").document(name).get()
                    .addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
                        @Override
                        public void onSuccess(DocumentSnapshot documentSnapshot) {
                            if (documentSnapshot.exists()) {
                                db.collection("users").document(friend).get()
                                    .addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
                                        @Override
                                        public void onSuccess(DocumentSnapshot documentSnapshot) {
                                            if (documentSnapshot.exists()) {
                                                prefsEdit.putString("username", name);
                                                prefsEdit.putString("friend", friend);
                                                prefsEdit.commit();

                                                Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                                                startActivity(intent);
                                            } else {
                                                Toast.makeText(LoginActivity.this, "No friend user found with that name", Toast.LENGTH_LONG).show();
                                            }
                                        }
                                    });
                            }
                            else {
                                Toast.makeText(LoginActivity.this, "No user found with that name", Toast.LENGTH_LONG).show();
                            }
                        }
                    });
            }
        });
    }
}
