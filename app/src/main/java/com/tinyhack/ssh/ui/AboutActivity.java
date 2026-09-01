package com.tinyhack.ssh.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.tinyhack.ssh.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * About screen with full license texts. Opens in one of two modes:
 *  - default: app info + tappable list of bundled licenses
 *  - EXTRA_ASSET/EXTRA_TITLE: full-text viewer for the given asset
 */
public class AboutActivity extends AppCompatActivity {
    public static final String EXTRA_ASSET = "asset";
    public static final String EXTRA_TITLE = "title";

    /** license display name → asset under assets/licenses/ */
    private static final String[][] LICENSES = {
        {"Third-Party Notices", "THIRD-PARTY-NOTICES.md"},
        {"Tinyhack SSH (this app) — MIT License", "LICENSE"},
        {"GNU Bash 5.2.37 — GPLv3", "bash-5.2.37.COPYING.GPLv3"},
        {"BusyBox 1.38.0 — GPLv2", "busybox-1.38.0.LICENSE.GPLv2"},
        {"Mosh 1.4.0 — GPLv3", "mosh-1.4.0.COPYING.GPLv3"},
        {"OpenSSH 10.5p1 — BSD-style", "openssh-10.5p1.LICENCE"},
        {"OpenSSL 3.5.8 — Apache-2.0", "openssl-3.5.8.LICENSE.Apache-2.0"},
        {"Ghostty terminal core — MIT", "ghostty-MIT.LICENSE"},
        {"protobuf 21.12 — BSD-3-Clause", "protobuf-21.12.LICENSE.BSD-3"},
        {"ncurses 6.5 — X11-style", "ncurses-6.5.COPYING"},
        {"rsync 3.5.0 — GPLv3", "rsync-3.5.0.COPYING.GPLv3"},
        {"zlib 1.3.1 — zlib license", "zlib-1.3.1.LICENSE"},
        {"cloudflared 2026.8.2 — Apache-2.0", "cloudflared-2026.8.2.LICENSE.Apache-2.0"},
        {"cloudflared dependencies — notices", "cloudflared-2026.8.2-THIRD-PARTY-NOTICES.txt"},
        {"Cascadia Code/Mono fonts — OFL 1.1", "font-cascadia.OFL-1.1"},
        {"Fira Code font — OFL 1.1", "font-fira-code.OFL-1.1"},
        {"Inconsolata font — OFL 1.1", "font-inconsolata.OFL-1.1"},
        {"JetBrains Mono font — OFL 1.1", "font-jetbrains-mono.OFL-1.1"},
        {"Nerd Fonts additions — license notices", "font-nerd-fonts.LICENSE"},
        {"Noto Mono font — OFL 1.1", "font-noto.OFL-1.1"},
        {"Hack font — MIT/Bitstream terms", "font-hack.LICENSE"},
        {"DejaVu Sans Mono — license notices", "font-dejavu.COPYRIGHT"},
        {"Ubuntu Mono — Ubuntu Font Licence", "font-ubuntu.UFL-1.0"},
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        String asset = getIntent().getStringExtra(EXTRA_ASSET);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (asset != null) {
            showLicenseText("licenses/" + asset, title != null ? title : "License");
        } else {
            showAboutInfo();
        }
    }

    private void showAboutInfo() {
        getSupportActionBar().setTitle("About");

        String version = "";
        try {
            version = "version " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        ((TextView) findViewById(R.id.about_version)).setText(version);

        TextView projectLink = findViewById(R.id.about_project_link);
        projectLink.setText(android.text.Html.fromHtml(
            "Project page: <a href=\"https://tinyhack.com/tinyhack-ssh/\">https://tinyhack.com/tinyhack-ssh/</a><br><br>"
                + "Tinyhack SSH is an independent, unofficial Android port. It uses the open-source Ghostty VT library and is not affiliated with or endorsed by the Ghostty project."));
        projectLink.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        TextView privacy = findViewById(R.id.about_privacy);
        privacy.setText(android.text.Html.fromHtml(
            "<b>Privacy policy:</b> we do not collect any data. Read the full policy at "
                + "<a href=\"https://tinyhack.com/tinyhack-ssh/privacy.html\">tinyhack.com/tinyhack-ssh/privacy.html</a>."));
        privacy.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        LinearLayout list = findViewById(R.id.license_list);
        for (final String[] entry : LICENSES) {
            TextView item = new TextView(this);
            item.setText("› " + entry[0]);
            item.setTextColor(0xFF7DA9FF);
            item.setTextSize(14);
            item.setPadding(0, dp(10), 0, dp(10));
            item.setClickable(true);
            item.setOnClickListener(v -> {
                Intent intent = new Intent(this, AboutActivity.class);
                intent.putExtra(EXTRA_ASSET, entry[1]);
                intent.putExtra(EXTRA_TITLE, entry[0]);
                startActivity(intent);
            });
            list.addView(item);
        }
    }

    private void showLicenseText(String assetPath, String title) {
        getSupportActionBar().setTitle(title);

        LinearLayout info = findViewById(R.id.about_info);
        info.removeAllViews();

        TextView text = new TextView(this);
        text.setTextColor(Color.parseColor("#E0E0E0"));
        text.setTextSize(12);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setPadding(0, dp(16), 0, dp(24));
        text.setText(readAsset(assetPath));
        info.addView(text);
    }

    private String readAsset(String path) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getAssets().open(path), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "Could not load license text: " + path;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
