package org.openstreetmap.josm.plugins.orbilume;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import javax.swing.Action;
import javax.swing.Icon;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.tools.ImageProvider;

final class EarthLayer extends Layer {
    private final EarthRaster.Image image;
    private final String attribution;

    EarthLayer(EarthRaster.Image image, Copernicus.Scene scene, Copernicus.Composite composite) {
        super("Orbilume • " + composite + " • " + scene.acquired().toString().substring(0, 10)
                + " • " + scene.id());
        this.image = image;
        this.attribution = scene.attribution() + " • Earth Search / Element 84";
        setBackgroundLayer(true);
    }

    @Override
    public void paint(Graphics2D graphics, MapView view, Bounds visible) {
        if (!isProjectionSupported(view.getProjection())) {
            return;
        }
        Bounds bounds = image.bounds();
        var nw = view.getPoint2D(new LatLon(bounds.getMaxLat(), bounds.getMinLon()));
        var se = view.getPoint2D(new LatLon(bounds.getMinLat(), bounds.getMaxLon()));
        var transform = new AffineTransform();
        transform.translate(nw.getX(), nw.getY());
        transform.scale((se.getX() - nw.getX()) / image.pixels().getWidth(),
                (se.getY() - nw.getY()) / image.pixels().getHeight());
        graphics.drawImage(image.pixels(), transform, null);
    }

    @Override
    public boolean isProjectionSupported(Projection projection) {
        return "EPSG:3857".equals(projection.toCode());
    }

    @Override
    public String nameSupportedProjections() {
        return "Web Mercator (EPSG:3857)";
    }

    @Override
    public Icon getIcon() {
        return ImageProvider.get("dialogs", "layerlist");
    }

    @Override
    public String getToolTipText() {
        return attribution;
    }

    @Override
    public Object getInfoComponent() {
        return attribution;
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void mergeFrom(Layer other) {
        throw new UnsupportedOperationException("Imagery layers cannot be merged.");
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor visitor) {
        visitor.visit(image.bounds());
    }

    @Override
    public Action[] getMenuEntries() {
        var dialog = LayerListDialog.getInstance();
        return new Action[]{dialog.createShowHideLayerAction(), dialog.createDeleteLayerAction()};
    }
}
