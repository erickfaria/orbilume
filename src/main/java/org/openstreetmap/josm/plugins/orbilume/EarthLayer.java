package org.openstreetmap.josm.plugins.orbilume;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.tools.ImageProvider;

final class EarthLayer extends Layer {
    record Frame(EarthRaster.Image image, Copernicus.Scene scene) {
        String date() {
            return scene.acquired().toString().substring(0, 10);
        }
    }

    private Frame current;
    private Frame alternate;
    private final Copernicus.Composite composite;

    EarthLayer(EarthRaster.Image image, Copernicus.Scene scene, Copernicus.Composite composite) {
        this(new Frame(image, scene), null, composite);
    }

    EarthLayer(Frame first, Frame second, Copernicus.Composite composite) {
        super(layerName(first, second, composite));
        this.current = first;
        this.alternate = second;
        this.composite = composite;
        setBackgroundLayer(true);
    }

    private static String layerName(Frame current, Frame alternate, Copernicus.Composite composite) {
        return "Orbilume • " + composite + " • " + current.date()
                + (alternate == null ? " • " + current.scene().id() : " (compare " + alternate.date() + ")");
    }

    @Override
    public void paint(Graphics2D graphics, MapView view, Bounds visible) {
        if (!isProjectionSupported(view.getProjection())) {
            return;
        }
        var image = current.image();
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
        return current.scene().attribution() + " • Earth Search / Element 84 • " + current.scene().id();
    }

    @Override
    public Object getInfoComponent() {
        return getToolTipText();
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
        visitor.visit(current.image().bounds());
    }

    @Override
    public Action[] getMenuEntries() {
        var dialog = LayerListDialog.getInstance();
        if (alternate == null) {
            return new Action[]{dialog.createShowHideLayerAction(), dialog.createDeleteLayerAction()};
        }
        var swap = new AbstractAction("Switch to " + alternate.date()) {
            @Override
            public void actionPerformed(ActionEvent event) {
                Frame previous = current;
                current = alternate;
                alternate = previous;
                putValue(NAME, "Switch to " + alternate.date());
                setName(layerName(current, alternate, composite));
                if (MainApplication.isDisplayingMapView()) {
                    MainApplication.getMap().mapView.repaint();
                }
            }
        };
        return new Action[]{swap, dialog.createShowHideLayerAction(), dialog.createDeleteLayerAction()};
    }
}
