/*
 * Copyright (c) 2016 United States Government as represented by the Administrator of the
 * National Aeronautics and Space Administration. All Rights Reserved.
 */

package gov.nasa.worldwind.layer;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.ogc.WmsLayerConfig;
import gov.nasa.worldwind.ogc.WmsTileFactory;
import gov.nasa.worldwind.ogc.wms.WmsCapabilities;
import gov.nasa.worldwind.ogc.wms.WmsLayerCapabilities;
import gov.nasa.worldwind.shape.TiledSurfaceImage;
import gov.nasa.worldwind.util.LevelSet;
import gov.nasa.worldwind.util.LevelSetConfig;
import gov.nasa.worldwind.util.Logger;

public class LayerFactory {

    public interface Callback {

        void creationSucceeded(LayerFactory factory, Layer layer);

        void creationFailed(LayerFactory factory, Layer layer, Throwable ex);
    }

    protected Handler mainLoopHandler = new Handler(Looper.getMainLooper());

    protected static final double DEFAULT_WMS_RADIANS_PER_PIXEL = 10.0 / WorldWind.WGS84_SEMI_MAJOR_AXIS;

    public LayerFactory() {
    }

    public Layer createFromGeoPackage(String pathName, Callback callback) {
        if (pathName == null) {
            throw new IllegalArgumentException(
                Logger.logMessage(Logger.ERROR, "LayerFactory", "createGeoPackageLayer", "missingPathName"));
        }

        if (callback == null) {
            throw new IllegalArgumentException(
                Logger.logMessage(Logger.ERROR, "LayerFactory", "createGeoPackageLayer", "missingCallback"));
        }

        RenderableLayer layer = new RenderableLayer();
        layer.setPickEnabled(false);

        GeoPackageAsyncTask task = new GeoPackageAsyncTask(this, pathName, layer, callback);

        try {
            WorldWind.taskService().execute(task);
        } catch (RejectedExecutionException logged) { // singleton task service is full; this should never happen but we check anyway
            callback.creationFailed(this, layer, logged);
        }

        return layer;
    }

    public Layer createFromWms(String serviceAddress, String layerNames, Callback callback) {
        if (serviceAddress == null) {
            throw new IllegalArgumentException(
                Logger.logMessage(Logger.ERROR, "LayerFactory", "createWmsLayer", "missingServiceAddress"));
        }

        if (layerNames == null) {
            throw new IllegalArgumentException(
                Logger.logMessage(Logger.ERROR, "LayerFactory", "createWmsLayer", "missingLayerNames"));
        }

        if (callback == null) {
            throw new IllegalArgumentException(
                Logger.logMessage(Logger.ERROR, "LayerFactory", "createWmsLayer", "missingCallback"));
        }

        RenderableLayer layer = new RenderableLayer();
        layer.setPickEnabled(false);

        WmsAsyncTask task = new WmsAsyncTask(this, serviceAddress, layerNames, layer, callback);

        try {
            WorldWind.taskService().execute(task);
        } catch (RejectedExecutionException logged) { // singleton task service is full; this should never happen but we check anyway
            callback.creationFailed(this, layer, logged);
        }

        return layer;
    }

    protected void createGeoPackageLayerAsync(String pathName, Layer layer, Callback callback) {
    }

    protected void createWmsLayerAsync(String serviceAddress, String layerNames, Layer layer, Callback callback) throws Exception {
        // Retrieve and parse the WMS capabilities at the specified service address, looking for the named layers
        // specified by the comma-delimited layerNames.
        Uri serviceUri = Uri.parse(serviceAddress).buildUpon()
            .appendQueryParameter("VERSION", "1.3.0")
            .appendQueryParameter("SERVICE", "WMS")
            .appendQueryParameter("REQUEST", "GetCapabilities")
            .build();

        // Parse and read capabilities document
        // TODO configurable connect and read timeouts
        URLConnection conn = new URL(serviceUri.toString()).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(30000);
        InputStream inputStream = new BufferedInputStream(conn.getInputStream());
        WmsCapabilities wmsCapabilities = WmsCapabilities.getCapabilities(inputStream);

        WmsLayerConfig wmsLayerConfig = new WmsLayerConfig();
        wmsLayerConfig.wmsVersion = wmsCapabilities.getVersion();

        String requestUrl = wmsCapabilities.getRequestURL("GetMap", "Get");
        if (requestUrl == null) {
            throw new IllegalStateException(
                Logger.makeMessage("LayerFactory", "createWmsLayerAsync", "Unable to resolve GetMap URL"));
        } else {
            wmsLayerConfig.serviceAddress = requestUrl;
        }

        WmsLayerCapabilities layerCapabilities = wmsCapabilities.getLayerByName(layerNames);
        if (layerCapabilities == null) {
            throw new IllegalArgumentException(
                Logger.makeMessage("LayerFactory", "createWmsLayerAsync", "Provided layer did not match available layers"));
        } else {
            wmsLayerConfig.layerNames = layerCapabilities.getName();
        }

        Set<String> coordinateSystems = layerCapabilities.getReferenceSystem();
        if (coordinateSystems.contains("EPSG:4326")) {
            wmsLayerConfig.coordinateSystem = "EPSG:4326";
        } else if (coordinateSystems.contains("CRS:84")) {
            wmsLayerConfig.coordinateSystem = "CRS:84";
        } else {
            throw new RuntimeException(
                Logger.makeMessage("LayerFactory", "createWmsLayerAsync", "Coordinate systems not compatible"));
        }

        Set<String> imageFormats = wmsCapabilities.getImageFormats();
        if (imageFormats.contains("image/png")) {
            wmsLayerConfig.imageFormat = "image/png";
        } else {
            wmsLayerConfig.imageFormat = imageFormats.iterator().next();
        }

        LevelSetConfig levelSetConfig = new LevelSetConfig();

        Sector sector = layerCapabilities.getGeographicBoundingBox();
        if (sector != null) {
            levelSetConfig.sector.set(sector);
        }

        if (layerCapabilities.getMinScaleDenominator() != null && layerCapabilities.getMinScaleDenominator() != 0) {
            // WMS 1.3.0 scale configuration. Based on the WMS 1.3.0 spec page 28. The hard coded value 0.00028 is
            // detailed in the spec as the common pixel size of 0.28mm x 0.28mm. Configures the maximum level not to
            // exceed the specified min scale denominator.
            double minMetersPerPixel = layerCapabilities.getMinScaleDenominator() * 0.00028;
            double minRadiansPerPixel = minMetersPerPixel / WorldWind.WGS84_SEMI_MAJOR_AXIS;
            levelSetConfig.numLevels = levelSetConfig.numLevelsForMinResolution(minRadiansPerPixel);
        } else if (layerCapabilities.getMinScaleHint() != null && layerCapabilities.getMinScaleHint() != 0) {
            // WMS 1.1.1 scale configuration, where ScaleHint indicates approximate resolution in ground distance
            // meters. Configures the maximum level not to exceed the specified min scale denominator.
            double minMetersPerPixel = layerCapabilities.getMinScaleHint();
            double minRadiansPerPixel = minMetersPerPixel / WorldWind.WGS84_SEMI_MAJOR_AXIS;
            levelSetConfig.numLevels = levelSetConfig.numLevelsForMinResolution(minRadiansPerPixel);
        } else {
            // Default scale configuration when no minimum scale denominator or scale hint is provided.
            double defaultRadiansPerPixel = DEFAULT_WMS_RADIANS_PER_PIXEL;
            levelSetConfig.numLevels = levelSetConfig.numLevelsForResolution(defaultRadiansPerPixel);
        }

        final TiledSurfaceImage surfaceImage = new TiledSurfaceImage();
        final RenderableLayer finalLayer = (RenderableLayer) layer;
        final Callback finalCallback = callback;

        surfaceImage.setTileFactory(new WmsTileFactory(wmsLayerConfig));
        surfaceImage.setLevelSet(new LevelSet(levelSetConfig));

        // Add the tiled surface image to the layer on the main thread and notify the caller. Request a redraw to ensure
        // that the image displays on all WorldWindows the layer may be attached to.
        this.mainLoopHandler.post(new Runnable() {
            @Override
            public void run() {
                finalLayer.addRenderable(surfaceImage);
                finalCallback.creationSucceeded(LayerFactory.this, finalLayer);
                WorldWind.requestRedraw();
            }
        });
    }

    protected static class GeoPackageAsyncTask implements Runnable {

        protected LayerFactory factory;

        protected String pathName;

        protected Layer layer;

        protected Callback callback;

        public GeoPackageAsyncTask(LayerFactory factory, String pathName, Layer layer, Callback callback) {
            this.factory = factory;
            this.pathName = pathName;
            this.layer = layer;
            this.callback = callback;
        }

        @Override
        public void run() {
            try {
                this.factory.createGeoPackageLayerAsync(this.pathName, this.layer, this.callback);
            } catch (Throwable ex) {
                this.callback.creationFailed(this.factory, this.layer, ex);
            }
        }
    }

    protected static class WmsAsyncTask implements Runnable {

        protected LayerFactory factory;

        protected String serviceAddress;

        protected String layerNames;

        protected Layer layer;

        protected Callback callback;

        public WmsAsyncTask(LayerFactory factory, String serviceAddress, String layerNames, Layer layer, Callback callback) {
            this.factory = factory;
            this.serviceAddress = serviceAddress;
            this.layerNames = layerNames;
            this.layer = layer;
            this.callback = callback;
        }

        @Override
        public void run() {
            try {
                this.factory.createWmsLayerAsync(this.serviceAddress, this.layerNames, this.layer, this.callback);
            } catch (Throwable ex) {
                this.callback.creationFailed(this.factory, this.layer, ex);
            }
        }
    }
}
