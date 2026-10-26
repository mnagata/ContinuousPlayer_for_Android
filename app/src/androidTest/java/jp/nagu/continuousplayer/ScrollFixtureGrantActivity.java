package jp.nagu.continuousplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Grant a real persistable tree from the provider's UID to the target app. */
public class ScrollFixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String[] trees = getIntent().getStringArrayExtra("trees");
        if (trees == null) trees = new String[]{ScrollFixtureProvider.tree.toString()};
        int access = getIntent().getIntExtra("accessFlags", Intent.FLAG_GRANT_READ_URI_PERMISSION);
        for (String tree : trees) {
            grantUriPermission(getIntent().getStringExtra("target"), android.net.Uri.parse(tree),
                access | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        finish();
    }
}
