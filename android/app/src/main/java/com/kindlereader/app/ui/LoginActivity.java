package com.kindlereader.app.ui;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.kindlereader.app.R;
import com.kindlereader.app.net.ApiClient;
import com.kindlereader.app.net.ApiException;
import com.kindlereader.app.util.Prefs;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Username + password login. On success stores the JWT + server base URL in
 * SharedPreferences (see {@link Prefs}) and moves to {@link LibraryActivity}.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText editUsername;
    private EditText editPassword;
    private Button btnLogin;
    private ProgressBar progress;
    private TextView textError;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        prefs = new Prefs(this);

        editUsername = (EditText) findViewById(R.id.edit_username);
        editPassword = (EditText) findViewById(R.id.edit_password);
        btnLogin = (Button) findViewById(R.id.btn_login);
        progress = (ProgressBar) findViewById(R.id.progress_login);
        textError = (TextView) findViewById(R.id.text_error);

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptLogin();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (prefs.isLoggedIn()) {
            goToLibrary();
        }
    }

    private void attemptLogin() {
        final String username = editUsername.getText().toString().trim();
        final String password = editPassword.getText().toString();

        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            showError(getString(R.string.error_missing_fields));
            return;
        }

        setLoading(true);
        new LoginTask().execute(username, password);
    }

    private void setLoading(boolean loading) {
        btnLogin.setEnabled(!loading);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            textError.setVisibility(View.GONE);
        }
    }

    private void showError(String message) {
        textError.setText(message);
        textError.setVisibility(View.VISIBLE);
    }

    private void goToLibrary() {
        startActivity(new Intent(this, LibraryActivity.class));
        finish();
    }

    /**
     * Plain AsyncTask (not a newer Executor/Future construct) -- available
     * since API 3, keeps this compatible with API 15 without extra libraries.
     */
    private class LoginTask extends AsyncTask<String, Void, LoginResult> {

        @Override
        protected LoginResult doInBackground(String... params) {
            String username = params[0];
            String password = params[1];
            ApiClient client = new ApiClient(LoginActivity.this);
            try {
                JSONObject response = client.login(username, password);
                LoginResult result = new LoginResult();
                result.token = response.optString("token", null);
                JSONObject user = response.optJSONObject("user");
                if (user != null) {
                    result.userId = user.optString("id", null);
                    result.username = user.optString("username", username);
                } else {
                    result.username = username;
                }
                return result;
            } catch (ApiException e) {
                LoginResult result = new LoginResult();
                result.error = e.getMessage();
                return result;
            }
        }

        @Override
        protected void onPostExecute(LoginResult result) {
            setLoading(false);
            if (result.error != null || TextUtils.isEmpty(result.token)) {
                String msg = result.error != null ? result.error : "unknown error";
                showError(getString(R.string.error_login_failed, msg));
                return;
            }
            prefs.setToken(result.token);
            prefs.setUser(result.userId, result.username);
            Toast.makeText(LoginActivity.this, R.string.app_name, Toast.LENGTH_SHORT).show();
            goToLibrary();
        }
    }

    private static class LoginResult {
        String token;
        String userId;
        String username;
        String error;
    }
}
