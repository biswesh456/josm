// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.io;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.json.JsonWriter;
import javax.json.stream.JsonGenerator;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.MultipolygonBuilder;
import org.openstreetmap.josm.data.osm.MultipolygonBuilder.JoinedPolygon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.AbstractVisitor;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.mappaint.ElemStyles;
import org.openstreetmap.josm.tools.Pair;

/**
 * Writes OSM data as a GeoJSON string, using JSR 353: Java API for JSON Processing (JSON-P).
 */
public class GeoJSONWriter {

    private final OsmDataLayer layer;
    private final Projection projection;
    private static final boolean skipEmptyNodes = true;

    /**
     * Constructs a new {@code GeoJSONWriter}.
     * @param layer The OSM data layer to save
     * @param projection The projection to use for coordinates
     */
    public GeoJSONWriter(OsmDataLayer layer, Projection projection) {
        this.layer = layer;
        this.projection = projection;
    }

    /**
     * Writes OSM data as a GeoJSON string (prettified).
     * @return The GeoJSON data
     */
    public String write() {
        return write(true);
    }

    /**
     * Writes OSM data as a GeoJSON string (prettified or not).
     * @param pretty {@code true} to have pretty output, {@code false} otherwise
     * @return The GeoJSON data
     * @since 6756
     */
    public String write(boolean pretty) {
        StringWriter stringWriter = new StringWriter();
        Map<String, Object> config = new HashMap<>(1);
        config.put(JsonGenerator.PRETTY_PRINTING, pretty);
        try (JsonWriter writer = Json.createWriterFactory(config).createWriter(stringWriter)) {
            JsonObjectBuilder object = Json.createObjectBuilder()
                    .add("type", "FeatureCollection")
                    .add("crs", Json.createObjectBuilder().add("type", "name").add(
                            "properties", Json.createObjectBuilder().add("name", projection.toCode())))
                    .add("generator", "JOSM");
            appendLayerBounds(layer.data, object);
            appendLayerFeatures(layer.data, object);
            writer.writeObject(object.build());
            return stringWriter.toString();
        }
    }

    private class GeometryPrimitiveVisitor extends AbstractVisitor {

        private final JsonObjectBuilder geomObj;

        GeometryPrimitiveVisitor(JsonObjectBuilder geomObj) {
            this.geomObj = geomObj;
        }

        @Override
        public void visit(Node n) {
            geomObj.add("type", "Point");
            LatLon ll = n.getCoor();
            if (ll != null) {
                geomObj.add("coordinates", getCoorArray(null, n.getCoor()));
            }
        }

        @Override
        public void visit(Way w) {
            if (w != null) {
                final JsonArrayBuilder array = getCoorsArray(w.getNodes());
                if (ElemStyles.hasAreaElemStyle(w, false)) {
                    final JsonArrayBuilder container = Json.createArrayBuilder().add(array);
                    geomObj.add("type", "Polygon");
                    geomObj.add("coordinates", container);
                } else {
                    geomObj.add("type", "LineString");
                    geomObj.add("coordinates", array);
                }
            }
        }

        @Override
        public void visit(Relation r) {
            if (r != null && r.isMultipolygon() && !r.hasIncompleteMembers()) {
                final Pair<List<JoinedPolygon>, List<JoinedPolygon>> mp = MultipolygonBuilder.joinWays(r);
                final JsonArrayBuilder polygon = Json.createArrayBuilder();
                Stream.concat(mp.a.stream(), mp.b.stream())
                        .map(p -> getCoorsArray(p.getNodes())
                                // since first node is not duplicated as last node
                                .add(getCoorArray(null, p.getNodes().get(0).getCoor())))
                        .forEach(polygon::add);
                geomObj.add("type", "MultiPolygon");
                final JsonArrayBuilder multiPolygon = Json.createArrayBuilder().add(polygon);
                geomObj.add("coordinates", multiPolygon);
            }
        }
    }

    private JsonArrayBuilder getCoorArray(JsonArrayBuilder builder, LatLon c) {
        return getCoorArray(builder, projection.latlon2eastNorth(c));
    }

    private static JsonArrayBuilder getCoorArray(JsonArrayBuilder builder, EastNorth c) {
        return builder != null ? builder : Json.createArrayBuilder()
                .add(BigDecimal.valueOf(c.getX()).setScale(11, RoundingMode.HALF_UP))
                .add(BigDecimal.valueOf(c.getY()).setScale(11, RoundingMode.HALF_UP));
    }

    private JsonArrayBuilder getCoorsArray(Iterable<Node> nodes) {
        final JsonArrayBuilder builder = Json.createArrayBuilder();
        for (Node n : nodes) {
            LatLon ll = n.getCoor();
            if (ll != null) {
                builder.add(getCoorArray(null, ll));
            }
        }
        return builder;
    }

    protected void appendPrimitive(OsmPrimitive p, JsonArrayBuilder array) {
        if (p.isIncomplete()) {
            return;
        } else if (skipEmptyNodes && p instanceof Node && p.getKeys().isEmpty()) {
            return;
        }

        // Properties
        final JsonObjectBuilder propObj = Json.createObjectBuilder();
        for (Entry<String, String> t : p.getKeys().entrySet()) {
            propObj.add(t.getKey(), t.getValue());
        }

        // Geometry
        final JsonObjectBuilder geomObj = Json.createObjectBuilder();
        p.accept(new GeometryPrimitiveVisitor(geomObj));

        // Build primitive JSON object
        array.add(Json.createObjectBuilder()
                .add("type", "Feature")
                .add("properties", propObj)
                .add("geometry", geomObj));
    }

    protected void appendLayerBounds(DataSet ds, JsonObjectBuilder object) {
        if (ds != null) {
            Iterator<Bounds> it = ds.getDataSourceBounds().iterator();
            if (it.hasNext()) {
                Bounds b = new Bounds(it.next());
                while (it.hasNext()) {
                    b.extend(it.next());
                }
                appendBounds(b, object);
            }
        }
    }

    protected void appendBounds(Bounds b, JsonObjectBuilder object) {
        if (b != null) {
            JsonArrayBuilder builder = Json.createArrayBuilder();
            getCoorArray(builder, b.getMin());
            getCoorArray(builder, b.getMax());
            object.add("bbox", builder);
        }
    }

    protected void appendLayerFeatures(DataSet ds, JsonObjectBuilder object) {
        JsonArrayBuilder array = Json.createArrayBuilder();
        if (ds != null) {
            ds.allPrimitives().forEach(p -> appendPrimitive(p, array));
        }
        object.add("features", array);
    }
}
