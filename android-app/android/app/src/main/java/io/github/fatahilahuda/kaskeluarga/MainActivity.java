package io.github.fatahilahuda.kaskeluarga;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must be registered before super.onCreate builds the bridge and loads the page.
        registerPlugin(KasShimPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
