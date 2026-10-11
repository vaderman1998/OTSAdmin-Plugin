package com.atakmap.android.otsadmin;

import android.annotation.SuppressLint;
import android.content.Context;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.PluginPreferenceFragment;

/**
 * Settings under ATAK Settings &gt; Tools &gt; OTS Admin.
 *
 * Layout in {@code res/xml/preferences.xml}. The password lives in ATAK's
 * preferences, which are not encrypted — the summary text says so rather
 * than leaving somebody to assume otherwise.
 */
public class OtsAdminPreferenceFragment extends PluginPreferenceFragment {

    @SuppressLint("StaticFieldLeak")
    private Context pluginContext;

    public OtsAdminPreferenceFragment() {
        super(MapView.getMapView().getContext(), R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public OtsAdminPreferenceFragment(final Context pluginContext) {
        super(pluginContext, R.xml.preferences);
        this.pluginContext = pluginContext;
    }

    @Override
    public String getSubTitle() {
        return super.getSubTitle();
    }
}
