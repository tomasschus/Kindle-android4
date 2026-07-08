package com.kindlereader.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.kindlereader.app.R;
import com.kindlereader.app.data.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple BaseAdapter (not RecyclerView) backing the library GridView -- kept
 * minimal to avoid an extra support-library artifact for a single small list.
 */
public class DocumentAdapter extends BaseAdapter {

    private final Context context;
    private final List<Document> items = new ArrayList<Document>();

    public DocumentAdapter(Context context) {
        this.context = context;
    }

    public void setItems(List<Document> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Document getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.item_document, parent, false);
        }
        Document doc = items.get(position);

        TextView title = (TextView) view.findViewById(R.id.text_title);
        TextView status = (TextView) view.findViewById(R.id.text_status);

        title.setText(doc.title);
        status.setText(statusLabel(doc));

        return view;
    }

    private String statusLabel(Document doc) {
        if (Document.STATUS_DOWNLOADED.equals(doc.downloadStatus)) {
            return context.getString(R.string.status_downloaded);
        } else if (Document.STATUS_DOWNLOADING.equals(doc.downloadStatus)) {
            return context.getString(R.string.status_downloading, doc.downloadProgress);
        } else if (Document.STATUS_FAILED.equals(doc.downloadStatus)) {
            return context.getString(R.string.status_failed);
        } else if (Document.STATUS_QUEUED.equals(doc.downloadStatus)) {
            return context.getString(R.string.status_queued);
        }
        return context.getString(R.string.status_not_downloaded);
    }
}
