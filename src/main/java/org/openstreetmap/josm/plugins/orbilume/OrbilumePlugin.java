package org.openstreetmap.josm.plugins.orbilume;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
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
import javax.swing.SwingWorker;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.imagery.ImageryInfo.ImageryType;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Logging;

public final class OrbilumePlugin extends Plugin {
    public OrbilumePlugin(PluginInformation information) {
        super(information);
        MainApplication.getMenu().imagerySubMenu.add(new AbstractAction("Orbilume") {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (!MainApplication.isDisplayingMapView()) {
                    JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                            "Open a map layer and zoom to the area you want to search.",
                            "Orbilume", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                new SceneDialog().setVisible(true);
            }
        });
    }

    static ImageryInfo imageryInfo(Copernicus.Scene scene) {
        ImageryInfo info = new ImageryInfo("Orbilume • " + scene);
        info.setImageryType(ImageryType.TMS);
        info.setExtendedUrl("tms:" + Copernicus.tileTemplate(scene.id()));
        info.setDefaultMaxZoom(14);
        info.setAttributionText(scene.attribution() + " • Copernicus EOPF Explorer");
        info.setAttributionLinkURL("https://explorer.eopf.copernicus.eu/");
        return info;
    }

    private static final class SceneDialog extends JDialog {
        private final Copernicus catalog = new Copernicus();
        private final JTextField start = new JTextField(LocalDate.now(ZoneOffset.UTC).minusDays(30).toString());
        private final JTextField end = new JTextField(LocalDate.now(ZoneOffset.UTC).toString());
        private final JSpinner clouds = new JSpinner(new SpinnerNumberModel(30, 0, 100, 1));
        private final DefaultListModel<Copernicus.Scene> results = new DefaultListModel<>();
        private final JList<Copernicus.Scene> scenes = new JList<>(results);
        private final JButton search = new JButton("Search");
        private final JButton load = new JButton("Load imagery");
        private final JLabel status = new JLabel("Search sends the map extent to Copernicus EOPF Explorer.");
        private SwingWorker<List<Copernicus.Scene>, Void> worker;

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
            filters.add(new JLabel("Maximum cloud cover (%)"));
            filters.add(clouds);
            body.add(filters, BorderLayout.NORTH);

            scenes.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            scenes.setVisibleRowCount(8);
            scenes.setFixedCellWidth(650);
            JScrollPane scroll = new JScrollPane(scenes);
            scroll.setBorder(BorderFactory.createTitledBorder("Available scenes"));
            body.add(scroll, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
            buttons.add(search);
            buttons.add(load);
            JPanel footer = new JPanel(new BorderLayout());
            footer.add(status, BorderLayout.CENTER);
            footer.add(buttons, BorderLayout.SOUTH);
            body.add(footer, BorderLayout.SOUTH);

            load.setEnabled(false);
            scenes.addListSelectionListener(event -> load.setEnabled(!scenes.isSelectionEmpty()));
            search.addActionListener(event -> search());
            load.addActionListener(event -> load());
            setContentPane(body);
            pack();
            setLocationRelativeTo(MainApplication.getMainFrame());
        }

        private void search() {
            if (!MainApplication.isDisplayingMapView()) {
                status.setText("Open a map layer first.");
                return;
            }
            URI uri;
            int maxCloud;
            try {
                uri = Copernicus.searchUri(MainApplication.getMap().mapView.getRealBounds(),
                        LocalDate.parse(start.getText().trim()), LocalDate.parse(end.getText().trim()));
                clouds.commitEdit();
                maxCloud = (Integer) clouds.getValue();
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
            results.clear();
            search.setEnabled(false);
            status.setText("Searching Copernicus…");
            worker = new SwingWorker<>() {
                @Override
                protected List<Copernicus.Scene> doInBackground() throws Exception {
                    return catalog.search(uri, maxCloud);
                }

                @Override
                protected void done() {
                    if (isCancelled() || !isDisplayable()) {
                        return;
                    }
                    search.setEnabled(true);
                    try {
                        List<Copernicus.Scene> found = get();
                        results.addAll(found);
                        status.setText(found.isEmpty() ? "No scenes matched in the first 20 catalog results."
                                : found.size() + " scenes • Copernicus Sentinel-2 L2A");
                        if (!found.isEmpty()) {
                            scenes.setSelectedIndex(0);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        status.setText("Search interrupted.");
                    } catch (ExecutionException e) {
                        Logging.warn(e.getCause());
                        String message = e.getCause().getMessage();
                        status.setText(message != null && message.startsWith("Copernicus")
                                ? message : "Unable to reach Copernicus. Check the network and retry.");
                    }
                }
            };
            worker.execute();
        }

        private void load() {
            Copernicus.Scene scene = scenes.getSelectedValue();
            if (scene == null || !MainApplication.isDisplayingMapView()) {
                status.setText("Select a scene with a map layer open.");
                return;
            }
            try {
                MainApplication.getLayerManager().addLayer(ImageryLayer.create(imageryInfo(scene)));
                status.setText("Imagery layer added. Check the layer for tile errors.");
            } catch (RuntimeException e) {
                Logging.warn(e);
                status.setText("Unable to add this imagery layer.");
            }
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
