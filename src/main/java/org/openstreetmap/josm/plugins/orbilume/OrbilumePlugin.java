package org.openstreetmap.josm.plugins.orbilume;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.imagery.ImageryInfo.ImageryType;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.OpenBrowser;

public final class OrbilumePlugin extends Plugin {
    public OrbilumePlugin(PluginInformation information) {
        super(information);
        var icon = new ImageProvider(information.iconPath)
                .setArchive(information.file)
                .setSize(16, 16)
                .get();
        MainApplication.getMenu().imagerySubMenu.add(new AbstractAction("Orbilume", icon) {
            @Override
            public void actionPerformed(ActionEvent event) {
                new SceneDialog().setVisible(true);
            }
        });
    }

    static ImageryInfo imageryInfo(Copernicus.Scene scene, Copernicus.Composite composite) {
        ImageryInfo info = new ImageryInfo("Orbilume • " + composite + " • " + scene);
        info.setImageryType(ImageryType.TMS);
        info.setExtendedUrl("tms:" + Copernicus.tileTemplate(scene.id(), composite));
        info.setDefaultMaxZoom(14);
        info.setAttributionText(scene.attribution() + " • Copernicus EOPF Explorer");
        info.setAttributionLinkURL("https://explorer.eopf.copernicus.eu/");
        return info;
    }

    private static final class SceneDialog extends JDialog {
        private final Copernicus catalog = new Copernicus();
        private final JTextField start = new JTextField(LocalDate.now(ZoneOffset.UTC).minusDays(30).toString());
        private final JTextField end = new JTextField(LocalDate.now(ZoneOffset.UTC).toString());
        private final JTextField aoi = new JTextField(38);
        private final JComboBox<Copernicus.Provider> provider = new JComboBox<>(Copernicus.Provider.values());
        private final JComboBox<Copernicus.Composite> composite = new JComboBox<>(Copernicus.Composite.values());
        private final JSpinner clouds = new JSpinner(new SpinnerNumberModel(30, 0, 100, 1));
        private final DefaultListModel<Copernicus.Scene> results = new DefaultListModel<>();
        private final JList<Copernicus.Scene> scenes = new JList<>(results);
        private final JButton search = new JButton("Search");
        private final JButton view = new JButton("Use map view");
        private final JButton selection = new JButton("Use selection");
        private final JButton preview = new JButton("Preview AOI");
        private final JButton compare = new JButton("Compare dates");
        private final JButton load = new JButton("Load imagery");
        private final JLabel status = new JLabel("Search sends the displayed AOI to the selected provider.");
        private SwingWorker<?, ?> worker;
        private boolean busy;
        private Bounds resultAoi;

        SceneDialog() {
            super(MainApplication.getMainFrame(), "Orbilume", Dialog.ModalityType.MODELESS);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            JPanel body = new JPanel(new BorderLayout(8, 8));
            body.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            JPanel filters = new JPanel(new GridLayout(0, 2, 8, 4));
            filters.add(new JLabel("Start date (YYYY-MM-DD)"));
            filters.add(start);
            filters.add(new JLabel("End date (YYYY-MM-DD)"));
            filters.add(end);
            filters.add(new JLabel("Maximum scene cloud cover (%)"));
            filters.add(clouds);
            filters.add(new JLabel("Imagery provider"));
            filters.add(provider);
            filters.add(new JLabel("Imagery composition"));
            filters.add(composite);

            JPanel area = new JPanel(new BorderLayout(6, 4));
            area.setBorder(BorderFactory.createTitledBorder("Area of interest (west, south, east, north)"));
            area.add(aoi, BorderLayout.NORTH);
            JPanel areaButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
            areaButtons.add(view);
            areaButtons.add(selection);
            area.add(areaButtons, BorderLayout.SOUTH);
            view.addActionListener(event -> useMapView());
            selection.addActionListener(event -> useSelection());
            JPanel options = new JPanel(new BorderLayout(0, 8));
            options.add(area, BorderLayout.NORTH);
            options.add(filters, BorderLayout.SOUTH);
            body.add(options, BorderLayout.NORTH);
            if (MainApplication.isDisplayingMapView()) {
                useMapView();
            }

            scenes.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            scenes.setVisibleRowCount(8);
            scenes.setFixedCellWidth(650);
            JScrollPane scroll = new JScrollPane(scenes);
            scroll.setBorder(BorderFactory.createTitledBorder("Available scenes"));
            body.add(scroll, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
            buttons.add(search);
            buttons.add(preview);
            buttons.add(compare);
            buttons.add(load);
            var sources = new JButton("Sources & licenses");
            sources.addActionListener(event -> {
                String error = OpenBrowser.displayUrl("https://github.com/erickfaria/orbilume/blob/main/docs/Sources-and-Licenses.md");
                if (error != null) {
                    status.setText("Unable to open the sources and licenses page in your browser.");
                }
            });
            JPanel footer = new JPanel(new BorderLayout());
            footer.add(status, BorderLayout.CENTER);
            JPanel actions = new JPanel(new BorderLayout(8, 0));
            actions.add(sources, BorderLayout.WEST);
            actions.add(buttons, BorderLayout.EAST);
            footer.add(actions, BorderLayout.SOUTH);
            body.add(footer, BorderLayout.SOUTH);

            preview.setToolTipText("Earth Search: preview the selected AOI and its estimated cloud cover");
            compare.setToolTipText("Earth Search: select two acquisitions on different dates with Ctrl or Shift");
            provider.addActionListener(event -> clearResults());
            clouds.addChangeListener(event -> clearResults());
            var edits = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { clearResults(); }
                @Override public void removeUpdate(DocumentEvent event) { clearResults(); }
                @Override public void changedUpdate(DocumentEvent event) { clearResults(); }
            };
            for (JTextField field : new JTextField[]{aoi, start, end}) {
                field.getDocument().addDocumentListener(edits);
            }
            scenes.addListSelectionListener(event -> updateActions());
            search.addActionListener(event -> search());
            preview.addActionListener(event -> preview());
            compare.addActionListener(event -> compare());
            load.addActionListener(event -> load());
            updateActions();
            setContentPane(body);
            pack();
            setLocationRelativeTo(MainApplication.getMainFrame());
        }

        private void useMapView() {
            if (!MainApplication.isDisplayingMapView()) {
                status.setText("Open a map or paste an AOI in decimal degrees.");
                return;
            }
            setAoi(MainApplication.getMap().mapView.getRealBounds());
        }

        private void useSelection() {
            var data = MainApplication.getLayerManager().getEditDataSet();
            if (data == null || data.getAllSelected().isEmpty()) {
                status.setText("Select a way or area in the active OSM data layer first.");
                return;
            }
            BBox bounds = new BBox();
            for (var object : data.getAllSelected()) {
                BBox objectBounds = object.getBBox();
                if (objectBounds == null || !objectBounds.isValid()) {
                    status.setText("The selection contains an object without usable bounds.");
                    return;
                }
                bounds.add(objectBounds);
            }
            setAoi(new Bounds(bounds.getMinLat(), bounds.getMinLon(),
                    bounds.getMaxLat(), bounds.getMaxLon(), false));
        }

        private void setAoi(Bounds bounds) {
            try {
                aoi.setText(Copernicus.bbox(bounds));
                status.setText("Search sends the displayed AOI to the selected provider.");
            } catch (IllegalArgumentException e) {
                status.setText(e.getMessage());
            }
        }

        private void search() {
            Bounds bounds;
            LocalDate from;
            LocalDate to;
            int maxCloud;
            var selectedProvider = (Copernicus.Provider) provider.getSelectedItem();
            try {
                bounds = Copernicus.parseAoi(aoi.getText());
                from = LocalDate.parse(start.getText().trim());
                to = LocalDate.parse(end.getText().trim());
                clouds.commitEdit();
                maxCloud = (Integer) clouds.getValue();
                Copernicus.searchUri(bounds, from, to, maxCloud, selectedProvider);
            } catch (DateTimeParseException e) {
                status.setText("Enter dates in YYYY-MM-DD format.");
                return;
            } catch (java.text.ParseException e) {
                status.setText("Enter cloud cover from 0 to 100.");
                return;
            } catch (IllegalArgumentException e) {
                status.setText(e.getMessage());
                return;
            }
            clearResults();
            runOperation("Searching " + selectedProvider + "…", "Unable to search the imagery catalog.",
                    () -> catalog.search(bounds, from, to, maxCloud, selectedProvider), found -> {
                        resultAoi = bounds;
                        results.addAll(found);
                        status.setText(found.isEmpty()
                                ? "No scenes. Try a wider date range or a higher scene cloud limit."
                                : found.size() + " scenes • Copernicus Sentinel-2 L2A");
                        if (!found.isEmpty()) {
                            scenes.setSelectedIndex(0);
                        }
                    });
        }

        private void clearResults() {
            resultAoi = null;
            results.clear();
            status.setText("Search sends the displayed AOI to the selected provider.");
            updateActions();
        }

        private void updateActions() {
            int count = scenes.getSelectedIndices().length;
            boolean earth = provider.getSelectedItem() == Copernicus.Provider.EARTH_SEARCH;
            load.setEnabled(!busy && count == 1);
            preview.setEnabled(!busy && earth && count == 1);
            compare.setEnabled(!busy && earth && count == 2);
        }

        private void setBusy(boolean value) {
            busy = value;
            for (var component : new java.awt.Component[]{aoi, start, end, provider, clouds, view,
                    selection, search, composite, scenes}) {
                component.setEnabled(!value);
            }
            updateActions();
        }

        private <T> void runOperation(String progress, String failure, Callable<T> task, Consumer<T> complete) {
            setBusy(true);
            status.setText(progress);
            var operation = new SwingWorker<T, Void>() {
                @Override
                protected T doInBackground() throws Exception {
                    return task.call();
                }

                @Override
                protected void done() {
                    if (isCancelled() || !isDisplayable()) {
                        return;
                    }
                    worker = null;
                    setBusy(false);
                    try {
                        complete.accept(get());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        status.setText("Operation interrupted.");
                    } catch (ExecutionException e) {
                        Logging.warn(e.getCause());
                        status.setText(e.getCause() instanceof IOException && e.getCause().getMessage() != null
                                ? e.getCause().getMessage() : failure);
                    } catch (RuntimeException e) {
                        Logging.warn(e);
                        status.setText(failure);
                    }
                }
            };
            worker = operation;
            operation.execute();
        }

        private void preview() {
            var scene = scenes.getSelectedValue();
            var bounds = resultAoi;
            runOperation("Preparing natural-color preview…", "Unable to show the preview.",
                    () -> EarthRaster.preview(scene, bounds), result -> {
                        var cover = result.coverage();
                        String stats = cover == null || cover.valid() == 0 ? "AOI cloud coverage is unavailable."
                                : String.format(Locale.ROOT,
                                        "AOI estimate: %.1f%% cloud • %.1f%% cloud shadow • %.1f%% valid coverage",
                                        cover.cloudPercent(), cover.shadowPercent(), cover.coveragePercent());
                        String date = scene.acquired().atOffset(ZoneOffset.UTC).toLocalDate().toString();
                        status.setText(date + " • " + stats);
                        var panel = new JPanel(new BorderLayout(0, 8));
                        var pixels = result.image().pixels();
                        double scale = Math.max(1, 320.0 / Math.max(pixels.getWidth(), pixels.getHeight()));
                        Image thumbnail = pixels.getScaledInstance(Math.max(1, (int) (scale * pixels.getWidth())),
                                Math.max(1, (int) (scale * pixels.getHeight())), Image.SCALE_SMOOTH);
                        panel.add(new JLabel(new ImageIcon(thumbnail), JLabel.CENTER), BorderLayout.CENTER);
                        var details = new JPanel(new GridLayout(0, 1, 0, 4));
                        details.add(new JLabel(stats));
                        details.add(new JLabel(scene.attribution() + " • Earth Search / Element 84"));
                        panel.add(details, BorderLayout.SOUTH);
                        JOptionPane.showMessageDialog(this, panel, "AOI preview • " + date,
                                JOptionPane.PLAIN_MESSAGE);
                    });
        }

        private void compare() {
            var dates = scenes.getSelectedValuesList().stream()
                    .sorted(Comparator.comparing(Copernicus.Scene::acquired)).toList();
            if (dates.size() != 2 || dates.get(0).acquired().atOffset(ZoneOffset.UTC).toLocalDate()
                    .equals(dates.get(1).acquired().atOffset(ZoneOffset.UTC).toLocalDate())) {
                status.setText("Select two scenes acquired on different dates.");
                return;
            }
            if (!checkCropProjection()) {
                return;
            }
            var composition = (Copernicus.Composite) composite.getSelectedItem();
            var bounds = resultAoi;
            runOperation("Loading both dates for the same AOI…", "Unable to load both dates.", () -> {
                var first = new EarthLayer.Frame(EarthRaster.load(dates.get(0), composition, bounds), dates.get(0));
                var second = new EarthLayer.Frame(EarthRaster.load(dates.get(1), composition, bounds), dates.get(1));
                return new EarthLayer.Frame[]{first, second};
            }, frames -> {
                MainApplication.getLayerManager().addLayer(new EarthLayer(frames[0], frames[1], composition));
                zoomTo(bounds);
                status.setText("Comparison added. Right-click its layer and choose Switch to [date].");
            });
        }

        private void load() {
            Copernicus.Scene scene = scenes.getSelectedValue();
            if (scene == null) {
                status.setText("Select a scene first.");
                return;
            }
            var selection = (Copernicus.Composite) composite.getSelectedItem();
            if (scene.provider() == Copernicus.Provider.EARTH_SEARCH) {
                loadCrop(scene, selection, resultAoi);
                return;
            }
            try {
                ImageryInfo info = imageryInfo(scene, selection);
                var layers = MainApplication.getLayerManager();
                boolean alreadyLoaded = layers.getLayersOfType(ImageryLayer.class).stream()
                        .anyMatch(layer -> info.getUrl().equals(layer.getInfo().getUrl()));
                if (alreadyLoaded) {
                    status.setText("This scene and composition are already open.");
                    return;
                }
                layers.addLayer(ImageryLayer.create(info));
                zoomTo(resultAoi);
                status.setText("Imagery layer added. Check the layer for tile errors.");
            } catch (RuntimeException e) {
                Logging.warn(e);
                status.setText("Unable to add this imagery layer.");
            }
        }

        private boolean checkCropProjection() {
            if (!"EPSG:3857".equals(ProjectionRegistry.getProjection().toCode())) {
                status.setText("Set the JOSM projection to Web Mercator (EPSG:3857) to load the crop.");
                return false;
            }
            return true;
        }

        private void loadCrop(Copernicus.Scene scene, Copernicus.Composite selection, Bounds bounds) {
            if (checkCropProjection()) {
                runOperation("Loading imagery for the searched AOI…", "Unable to load the image crop.",
                        () -> EarthRaster.load(scene, selection, bounds), image -> {
                            MainApplication.getLayerManager().addLayer(new EarthLayer(image, scene, selection));
                            zoomTo(bounds);
                            status.setText("Imagery added • " + scene.attribution() + " • Earth Search");
                        });
            }
        }

        private static void zoomTo(Bounds bounds) {
            SwingUtilities.invokeLater(() -> {
                if (bounds != null && MainApplication.isDisplayingMapView()) {
                    MainApplication.getMap().mapView.zoomTo(bounds);
                }
            });
        }

        @Override
        public void dispose() {
            if (worker != null) {
                worker.cancel(true);
            }
            super.dispose();
        }
    }
}
