package com.kindlereader.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.kindlereader.app.R;
import com.kindlereader.app.util.Prefs;

/** Account info + logout. */
public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private TextView textLoggedInAs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.title_settings);

        prefs = new Prefs(this);

        textLoggedInAs = (TextView) findViewById(R.id.text_logged_in_as);
        Button btnLogout = (Button) findViewById(R.id.btn_logout);

        String username = prefs.getUsername();
        textLoggedInAs.setText(getString(R.string.label_logged_in_as,
                TextUtils.isEmpty(username) ? "?" : username));

        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setMessage(R.string.action_confirm_logout)
                        .setPositiveButton(R.string.action_logout, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                prefs.clearSession();
                                Intent intent = new Intent(SettingsActivity.this, LoginActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            }
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
        });
    }
}
