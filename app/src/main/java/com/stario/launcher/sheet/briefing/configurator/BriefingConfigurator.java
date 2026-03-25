/*
 * Copyright (C) 2025 Răzvan Albu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 */

package com.stario.launcher.sheet.briefing.configurator;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.prof18.rssparser.model.RssChannel;
import com.stario.launcher.R;
import com.stario.launcher.Stario;
import com.stario.launcher.sheet.briefing.dialog.page.feed.BriefingFeedList;
import com.stario.launcher.sheet.briefing.dialog.page.feed.Feed;
import com.stario.launcher.sheet.briefing.rss.RSSHelper;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.common.text.PulsingTextView;
import com.stario.launcher.ui.dialogs.ActionDialog;
import com.stario.launcher.ui.utils.UiUtils;
import com.stario.launcher.utils.Utils;

import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import carbon.view.SimpleTextWatcher;

public class BriefingConfigurator extends ActionDialog {
    private static final String TAG = "BriefingConfigurator";
    private static final long DEBOUNCE_DELAY = 300;
    private static final int JSOUP_TIMEOUT = 5000;
    private static final int FEEDLY_TIMEOUT = 8000;
    private static final int FEEDLY_RESULT_COUNT = 5;
    private static final String FEEDLY_SEARCH_URL = "https://cloud.feedly.com/v3/search/feeds";

    private final BriefingFeedList list;

    private volatile Future<?> currentSearchTask;
    private volatile Feed validatedFeed;

    private Runnable debounceRunnable;
    private ViewGroup contentView;
    private PulsingTextView limit;
    private LinearLayout preview;
    private LinearLayout searchResults;
    private TextView title;
    private EditText query;

    public BriefingConfigurator(@NonNull ThemedActivity activity) {
        super(activity);

        this.list = BriefingFeedList.from(activity);
    }

    @Override
    protected @NonNull View inflateContent(LayoutInflater inflater) {
        contentView = (ViewGroup) inflater.inflate(R.layout.briefing_configurator, null);

        query = contentView.findViewById(R.id.query);
        preview = contentView.findViewById(R.id.preview);
        title = contentView.findViewById(R.id.title);
        limit = contentView.findViewById(R.id.limit);
        searchResults = contentView.findViewById(R.id.search_results);

        query.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(@NonNull CharSequence sequence,
                                      int start, int before, int count) {
                if (debounceRunnable != null) {
                    UiUtils.removeUICallback(debounceRunnable);
                }

                if (currentSearchTask != null && !currentSearchTask.isDone()) {
                    currentSearchTask.cancel(true);
                }

                String text = sequence.toString();

                if (text.isEmpty()) {
                    showStatus(null, false);

                    return;
                }

                if (!Utils.isNetworkAvailable(activity)) {
                    showStatus(R.string.no_connection, false);

                    return;
                }

                String validUrl = null;
                if (isValidUrl(text)) {
                    validUrl = text;
                } else {
                    String potentialUrl = "https://" + text;

                    if (isValidUrl(potentialUrl)) {
                        validUrl = potentialUrl;
                    }
                }

                if (validUrl != null) {
                    // URL mode: use existing discovery flow
                    String finalValidUrl = validUrl;
                    debounceRunnable = () -> {
                        showStatus(R.string.searching, true);
                        currentSearchTask = Utils.submitTask(
                                new FeedDiscoveryTask(activity.getApplicationContext(),
                                        new String[]{
                                                finalValidUrl,
                                                finalValidUrl.replaceAll("/$", "") + ".rss"
                                        })
                        );
                    };
                } else {
                    // Natural language mode: use Feedly search
                    debounceRunnable = () -> {
                        showStatus(R.string.searching, true);
                        currentSearchTask = Utils.submitTask(new FeedSearchTask(text));
                    };
                }

                UiUtils.postDelayed(debounceRunnable, DEBOUNCE_DELAY);
            }
        });

        contentView.findViewById(R.id.add).setOnClickListener((view) -> {
            view.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.bounce_small));

            if (validatedFeed != null && validatedFeed.getTitle() != null &&
                    !validatedFeed.getTitle().isEmpty()) {
                addFeedAndDismiss(validatedFeed);
            }
        });

        contentView.findViewById(R.id.paste).setOnClickListener((v) -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);

            if (clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();

                if (clip != null) {
                    if (clip.getDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                        String text = clip.getItemAt(0)
                                .coerceToText(activity).toString();

                        if (!text.isEmpty()) {
                            query.setText(text);
                            query.setSelection(text.length());
                        }
                    }
                }
            }
        });

        return contentView;
    }

    @Override
    public void show() {
        if (query != null) {
            query.setText(null);
        }

        super.show();
    }

    private boolean isValidUrl(String url) {
        return URLUtil.isValidUrl(url) && Patterns.WEB_URL.matcher(url).matches();
    }

    private void showStatus(Integer messageRes, boolean pulsating) {
        if (preview != null) {
            preview.setVisibility(View.GONE);
        }

        if (searchResults != null) {
            searchResults.setVisibility(View.GONE);
            searchResults.removeAllViews();
        }

        if (limit != null) {
            if (messageRes != null) {
                limit.setText(activity.getResources().getString(messageRes));
                limit.setPulsating(pulsating);
                limit.setVisibility(View.VISIBLE);
            } else {
                limit.setVisibility(View.GONE);
            }
        }

        validatedFeed = null;
    }

    private void showPreview(@NonNull Feed feed) {
        validatedFeed = feed;

        title.setText(feed.getTitle());
        preview.setVisibility(View.VISIBLE);
        limit.setVisibility(View.GONE);

        if (searchResults != null) {
            searchResults.setVisibility(View.GONE);
            searchResults.removeAllViews();
        }
    }

    private void showSearchResults(@NonNull List<Feed> feeds) {
        validatedFeed = null;
        preview.setVisibility(View.GONE);
        limit.setVisibility(View.GONE);
        searchResults.removeAllViews();

        if (feeds.isEmpty()) {
            showStatus(R.string.no_feeds_found, false);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(activity);

        for (Feed feed : feeds) {
            View row = inflater.inflate(R.layout.briefing_search_result, searchResults, false);

            TextView rowTitle = row.findViewById(R.id.result_title);
            rowTitle.setText(feed.getTitle());

            row.findViewById(R.id.result_add).setOnClickListener(v -> {
                v.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.bounce_small));
                addFeedAndDismiss(feed);
            });

            searchResults.addView(row);
        }

        searchResults.setVisibility(View.VISIBLE);
    }

    private void addFeedAndDismiss(@NonNull Feed feed) {
        boolean added = list.add(feed);

        if (added) {
            BottomSheetBehavior<?> behavior = getBehavior();
            behavior.setDraggable(false);
            behavior.setState(BottomSheetBehavior.STATE_HIDDEN);

            UiUtils.hideKeyboard(contentView);
        } else {
            Toast.makeText(activity, R.string.already_subscribed, Toast.LENGTH_LONG).show();
        }
    }

    // ---- Natural language feed search via Feedly ----

    private class FeedSearchTask implements Runnable {
        private final String queryText;

        private FeedSearchTask(String queryText) {
            this.queryText = queryText;
        }

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            try {
                String encoded = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name());
                String urlString = FEEDLY_SEARCH_URL + "?query=" + encoded + "&count=" + FEEDLY_RESULT_COUNT;

                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(FEEDLY_TIMEOUT);
                connection.setReadTimeout(FEEDLY_TIMEOUT);
                connection.setRequestProperty("User-Agent", Utils.USER_AGENT);

                int responseCode = connection.getResponseCode();

                if (Thread.currentThread().isInterrupted()) {
                    connection.disconnect();
                    return;
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    connection.disconnect();
                    contentView.post(() -> showStatus(R.string.no_feeds_found, false));
                    return;
                }

                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                connection.disconnect();

                if (Thread.currentThread().isInterrupted()) {
                    return;
                }

                List<Feed> feeds = parseFeedlyResponse(response.toString());

                contentView.post(() -> showSearchResults(feeds));

            } catch (Exception e) {
                Log.e(TAG, "FeedSearchTask error: " + e.getMessage());

                if (!Thread.currentThread().isInterrupted()) {
                    contentView.post(() -> showStatus(R.string.no_feeds_found, false));
                }
            }
        }

        private List<Feed> parseFeedlyResponse(String json) {
            List<Feed> feeds = new ArrayList<>();

            try {
                FeedlyResponse response = new Gson().fromJson(json, FeedlyResponse.class);

                if (response == null || response.results == null) {
                    return feeds;
                }

                for (FeedlyResult result : response.results) {
                    if (result.feedId == null) continue;

                    // feedId format: "feed/https://rss-url.com/feed"
                    String rssUrl = result.feedId.startsWith("feed/")
                            ? result.feedId.substring(5)
                            : result.feedId;

                    if (rssUrl.isEmpty()) continue;

                    String feedTitle = (result.title != null && !result.title.isEmpty())
                            ? result.title
                            : activity.getString(R.string.unknown_feed);

                    feeds.add(new Feed(feedTitle, rssUrl));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse Feedly response: " + e.getMessage());
            }

            return feeds;
        }
    }

    private static class FeedlyResult {
        @SerializedName("feedId")
        String feedId;

        @SerializedName("title")
        String title;
    }

    private static class FeedlyResponse {
        @SerializedName("results")
        List<FeedlyResult> results;
    }

    // ---- URL-based feed discovery (unchanged) ----

    private class FeedDiscoveryTask implements Runnable {
        private final Stario context;
        private final String[] urls;

        private FeedDiscoveryTask(Stario context, String[] urls) {
            this.context = context;
            this.urls = urls;
        }

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            for (String url : urls) {
                try {
                    Feed feed = attemptParse(url);

                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    if (feed != null) {
                        contentView.post(() -> showPreview(feed));

                        return;
                    }

                    String discoveredFeedUrl = discoverFeedUrl(url);

                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    if (discoveredFeedUrl != null) {
                        Feed discoveredFeed = attemptParse(discoveredFeedUrl);

                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }

                        if (discoveredFeed != null) {
                            contentView.post(() -> showPreview(discoveredFeed));

                            return;
                        }
                    }
                } catch (IOException exception) {
                    Log.e(TAG, "IOException: " + exception.getMessage());
                }
            }

            contentView.post(() -> showStatus(R.string.invalid_rss, false));
        }

        private Feed attemptParse(String url) {
            CompletableFuture<@NotNull RssChannel> streamFuture = null;

            try {
                streamFuture = RSSHelper.futureParse(url);
                String feedTitle = streamFuture.get(10, TimeUnit.SECONDS).getTitle();

                if (feedTitle == null) {
                    feedTitle = context.getString(R.string.unknown_feed);
                }

                return new Feed(feedTitle, url);
            } catch (InterruptedException | TimeoutException exception) {
                streamFuture.cancel(true);

                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception exception) {
                if (streamFuture != null) {
                    streamFuture.cancel(true);
                }
            }

            return null;
        }

        private String discoverFeedUrl(String url) throws IOException {
            Document document = Jsoup.connect(url)
                    .userAgent(Utils.USER_AGENT)
                    .timeout(JSOUP_TIMEOUT)
                    .get();

            Elements nodes = document.select("link[type*=\"rss\"], link[type*=\"atom\"]");

            for (Element node : nodes) {
                if (node.hasAttr("href")) {
                    return node.attr("abs:href");
                }
            }

            return null;
        }
    }

    @Override
    protected boolean blurBehind() {
        return true;
    }

    @Override
    protected int getDesiredInitialState() {
        return BottomSheetBehavior.STATE_EXPANDED;
    }
}
