package com.kindlereader.app.ui;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import com.kindlereader.app.R;
import com.kindlereader.app.data.DbHelper;
import com.kindlereader.app.data.Document;
import com.kindlereader.app.net.SyncManager;
import com.kindlereader.app.util.Prefs;

import java.util.List;

/**
 * Library / sync screen: shows locally-known documents (offline-first) and
 * lets the user trigger a sync (pull-to-refresh or the Sync menu action).
 */
public class LibraryActivity extends AppCompatActivity {

    private SwipeRefreshLayout swipeRefresh;
    private GridView grid;
    private TextView emptyView;
    private DocumentAdapter adapter;
    private DbHelper db;
    private SyncManager syncManager;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        setTitle(R.string.title_library);

        prefs = new Prefs(this);
        db = DbHelper.getInstance(this);
        syncManager = new SyncManager(this);
        adapter = new DocumentAdapter(this);

        swipeRefresh = (SwipeRefreshLayout) findViewById(R.id.swipe_refresh);
        grid = (GridView) findViewById(R.id.grid_documents);
        emptyView = (TextView) findViewById(R.id.text_empty);

        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openDocument(adapter.getItem(position));
            }
        });

        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                runSync();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!prefs.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        reloadFromDb();
        runSync();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_library, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sync) {
            runSync();
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_logout) {
            confirmLogout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.action_confirm_logout)
                .setPositiveButton(R.string.action_logout, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        prefs.clearSession();
                        startActivity(new Intent(LibraryActivity.this, LoginActivity.class));
                        finish();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void openDocument(Document doc) {
        if (!doc.isDownloaded()) {
            Toast.makeText(this, R.string.status_not_downloaded, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, doc.id);
        startActivity(intent);
    }

    private void runSync() {
        swipeRefresh.setRefreshing(true);
        syncManager.syncNow(new SyncManager.SyncListener() {
            @Override
            public void onSyncFinished(boolean success, String errorMessage) {
                swipeRefresh.setRefreshing(false);
                reloadFromDb();
                if (!success) {
                    Toast.makeText(LibraryActivity.this,
                            getString(R.string.sync_error, String.valueOf(errorMessage)),
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onDocumentDownloaded(String documentId, boolean success) {
                reloadFromDb();
            }
        });
    }

    private void reloadFromDb() {
        new LoadTask().execute();
    }

    private class LoadTask extends AsyncTask<Void, Void, List<Document>> {
        @Override
        protected List<Document> doInBackground(Void... params) {
            return db.getAllDocuments();
        }

        @Override
        protected void onPostExecute(List<Document> documents) {
            adapter.setItems(documents);
            emptyView.setVisibility(documents.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }
}
