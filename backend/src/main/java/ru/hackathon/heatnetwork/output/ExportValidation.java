package ru.hackathon.heatnetwork.output;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Point;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;

/** Structural export checks. Engineering rules and admissibility remain the calculator's job. */
final class ExportValidation {
    static final double POSITION_TOLERANCE_M = 0.001;
    private ExportValidation() { }

    static List<PreparedVariant> prepare(Dataset dataset, List<CalculatedVariant> variants,
                                         Wgs84Writer projection) throws IOException {
        require(dataset != null, "Для экспорта необходим Dataset.");
        require(variants != null && !variants.isEmpty() && variants.size() <= 3, "Ожидается от одного до трёх рассчитанных вариантов.");
        List<PreparedVariant> prepared = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        BigDecimal previousScore = null;
        Mode mode = null;
        for (CalculatedVariant variant : variants) {
            interrupted();
            require(variant != null && text(variant.variantId), "У варианта должен быть непустой variantId.");
            require(ids.add(variant.variantId), "Повторяющийся variantId: " + variant.variantId);
            require(variant.mode != null, "Не задан режим рассчитанного варианта.");
            require(mode == null || mode == variant.mode, "В одном экспорте нельзя смешивать TWO_D и DEPTH.");
            mode = variant.mode;
            require(variant.nodes != null && variant.edges != null && variant.newChambers != null
                    && variant.unconnectedPointIds != null, "Списки варианта не могут быть null.");
            PreparedVariant current = prepareVariant(dataset, variant, projection);
            require(previousScore == null || previousScore.compareTo(variant.summary.score) <= 0,
                    "Варианты должны быть заранее отсортированы по возрастанию score.");
            previousScore = variant.summary.score;
            prepared.add(current);
        }
        return prepared;
    }

    private static PreparedVariant prepareVariant(Dataset dataset, CalculatedVariant variant,
                                                   Wgs84Writer projection) throws IOException {
        PreparedVariant prepared = new PreparedVariant(variant);
        Set<ObjectId> externalNodes = new HashSet<>();
        Set<ObjectId> connected = new HashSet<>();
        for (Node node : variant.nodes) {
            interrupted();
            require(node != null && text(node.id) && node.kind != null, "Некорректное описание узла.");
            require(!prepared.nodes.containsKey(node.id), "Повторяющийся внутренний ID узла: " + node.id);
            point(node.geometry, projection);
            Point location = node.geometry;
            if (node.kind == NodeKind.EXISTING_CHAMBER || node.kind == NodeKind.CONNECTION_POINT) {
                require(node.inputObjectId != null, "Для существующей камеры/точки подключения нужен inputObjectId: " + node.id);
                require(externalNodes.add(node.inputObjectId), "Исходный узел представлен в варианте несколько раз: " + node.id);
                InputType expected = node.kind == NodeKind.EXISTING_CHAMBER ? InputType.HEAT_CHAMBER : InputType.OKS_CONNECTION_POINT;
                InputObject original = source(dataset, node.inputObjectId, expected);
                require(original.geometry instanceof Point, "Исходный узел должен быть Point: " + node.id);
                location = (Point) original.geometry;
                point(location, projection);
                require(near(node.geometry.getX(), node.geometry.getY(), location), "Узел смещён относительно исходного объекта: " + node.id);
                if (node.kind == NodeKind.CONNECTION_POINT) connected.add(node.inputObjectId);
            } else {
                require(node.inputObjectId == null, "Новый узел не должен ссылаться на исходный объект: " + node.id);
            }
            prepared.nodes.put(node.id, new PreparedNode(node, location));
        }

        BigDecimal pipeCost = BigDecimal.ZERO;
        double length = 0;
        Set<String> edgeIds = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        DepthExportValidation depth = variant.mode == Mode.DEPTH ? new DepthExportValidation() : null;
        for (CalculatedEdge edge : variant.edges) {
            interrupted();
            require(edge != null && text(edge.id) && edgeIds.add(edge.id), "Некорректный или повторяющийся ID участка.");
            PreparedNode from = prepared.nodes.get(edge.fromNodeId);
            PreparedNode to = prepared.nodes.get(edge.toNodeId);
            require(from != null && to != null && !Objects.equals(edge.fromNodeId, edge.toNodeId), "Участок ссылается на отсутствующие или одинаковые узлы: " + edge.id);
            require(edge.geometry != null && edge.geometry.getSRID() == Model.METRIC_SRID
                    && edge.geometry.getNumPoints() >= 2, "Геометрия участка должна быть LineString в EPSG:32637: " + edge.id);
            CoordinateSequence coordinates = edge.geometry.getCoordinateSequence();
            for (int i = 0; i < coordinates.size(); i++) {
                if ((i & 1023) == 0) interrupted();
                projection.check(coordinates.getX(i), coordinates.getY(i));
            }
            int end = coordinates.size() - 1;
            require(near(coordinates.getX(0), coordinates.getY(0), from.location)
                    && near(coordinates.getX(end), coordinates.getY(end), to.location),
                    "Концы LineString не совпадают с указанными узлами: " + edge.id);
            double geometryLength = edge.geometry.getLength();
            double alignedLength = alignedLength(coordinates, geometryLength, from.location, to.location);
            require(Double.isFinite(edge.lengthM) && edge.lengthM > 0 && geometryLength > 0
                    && alignedLength > 0 && close(geometryLength, edge.lengthM) && close(alignedLength, edge.lengthM),
                    "length не соответствует метрической геометрии: " + edge.id);
            number(edge.flowTph, "flow_tph");
            number(edge.costRub, "cost");
            require(edge.diameterMm > 0 && edge.layingMethod != null, "Не заданы диаметр или способ прокладки: " + edge.id);
            if (depth == null) {
                require(edge.depthStartM == null && edge.depthEndM == null, "В режиме 2D глубина должна быть null: " + edge.id);
            } else {
                depth.check(edge);
            }
            pipeCost = pipeCost.add(edge.costRub);
            length += edge.lengthM;
            referenced.add(edge.fromNodeId);
            referenced.add(edge.toNodeId);
        }
        for (PreparedNode node : prepared.nodes.values()) {
            require(referenced.contains(node.node.id), "Узел не используется ни одним участком: " + node.node.id);
        }

        BigDecimal chamberCost = BigDecimal.ZERO;
        for (ChamberCost chamber : variant.newChambers) {
            interrupted();
            require(chamber != null && chamber.diameterMm > 0, "Некорректная стоимость новой камеры.");
            PreparedNode node = prepared.nodes.get(chamber.nodeId);
            require(node != null && node.node.kind == NodeKind.NEW_CHAMBER, "Стоимость камеры ссылается не на новую камеру.");
            require(node.chamber == null, "Повторяющаяся стоимость камеры: " + chamber.nodeId);
            number(chamber.costRub, "cost камеры");
            node.chamber = chamber;
            chamberCost = chamberCost.add(chamber.costRub);
        }
        for (PreparedNode node : prepared.nodes.values()) {
            require(node.node.kind != NodeKind.NEW_CHAMBER || node.chamber != null, "Не задана стоимость новой камеры: " + node.node.id);
        }

        Set<ObjectId> unconnected = new HashSet<>();
        for (ObjectId id : variant.unconnectedPointIds) {
            interrupted();
            require(id != null && unconnected.add(id), "Повторяющийся или пустой ID неподключённой точки.");
            require(!connected.contains(id), "Точка одновременно подключена и указана как неподключённая.");
            source(dataset, id, InputType.OKS_CONNECTION_POINT);
        }
        try (Stream<InputObject> targets = dataset.objects(InputType.OKS_CONNECTION_POINT)) {
            Iterator<InputObject> iterator = targets.iterator();
            while (iterator.hasNext()) {
                interrupted();
                ObjectId id = iterator.next().id;
                require(connected.contains(id) || unconnected.contains(id), "В варианте потеряна входная точка подключения.");
            }
        }
        summary(variant.summary, pipeCost, chamberCost, length);
        return prepared;
    }

    private static void summary(Summary summary, BigDecimal pipes, BigDecimal chambers, double length) throws InvalidResultException {
        require(summary != null, "Не задана сводка варианта.");
        number(summary.constructionCost, "construction_cost");
        number(summary.chamberConstructionCost, "chamber_construction_cost");
        number(summary.existingChamberTieInCost, "existing_chamber_tie_in_cost");
        number(summary.unconnectedPenalty, "unconnected_penalty");
        number(summary.calculatedCost, "calculated_cost");
        number(summary.score, "score");
        require(summary.existingChamberTieInCount >= 0, "Количество врезок не может быть отрицательным.");
        require(Double.isFinite(summary.newNetworkLength) && summary.newNetworkLength >= 0
                && close(summary.newNetworkLength, length), "new_network_length не совпадает с суммой length участков.");
        require(chambers.compareTo(summary.chamberConstructionCost) == 0, "chamber_construction_cost не совпадает с суммой стоимостей камер.");
        require(pipes.add(chambers).add(summary.existingChamberTieInCost).compareTo(summary.constructionCost) == 0,
                "construction_cost не совпадает с суммой стоимостей строительства.");
        require(summary.constructionCost.add(summary.unconnectedPenalty).compareTo(summary.calculatedCost) == 0,
                "calculated_cost не совпадает с construction_cost + unconnected_penalty.");
    }

    private static InputObject source(Dataset dataset, ObjectId id, InputType expected) throws InvalidResultException {
        InputObject object = dataset.find(id).orElse(null);
        require(object != null && object.type == expected && id.equals(object.id), "Ссылка на отсутствующий исходный объект или объект неподходящего типа.");
        return object;
    }

    private static void point(Point point, Wgs84Writer projection) throws InvalidResultException {
        require(point != null && !point.isEmpty() && point.getSRID() == Model.METRIC_SRID, "Узел должен быть Point в EPSG:32637.");
        projection.check(point.getX(), point.getY());
    }

    private static boolean near(double x, double y, Point point) { return Math.hypot(x - point.getX(), y - point.getY()) <= POSITION_TOLERANCE_M; }
    private static double alignedLength(CoordinateSequence coordinates, double length, Point from, Point to) {
        if (coordinates.size() == 2) return Math.hypot(from.getX() - to.getX(), from.getY() - to.getY());
        int last = coordinates.size() - 1;
        double startDelta = Math.hypot(from.getX() - coordinates.getX(1), from.getY() - coordinates.getY(1))
                - Math.hypot(coordinates.getX(0) - coordinates.getX(1), coordinates.getY(0) - coordinates.getY(1));
        double endDelta = Math.hypot(to.getX() - coordinates.getX(last - 1), to.getY() - coordinates.getY(last - 1))
                - Math.hypot(coordinates.getX(last) - coordinates.getX(last - 1), coordinates.getY(last) - coordinates.getY(last - 1));
        return length + startDelta + endDelta;
    }
    private static boolean close(double left, double right) { return Double.isFinite(left) && Double.isFinite(right) && Math.abs(left - right) <= POSITION_TOLERANCE_M; }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void number(BigDecimal number, String field) throws InvalidResultException { require(number != null && number.signum() >= 0, "Поле " + field + " должно быть неотрицательным числом."); }
    private static void require(boolean condition, String message) throws InvalidResultException { if (!condition) throw new InvalidResultException(message); }
    static void interrupted() throws InterruptedIOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Экспорт прерван."); }

    static final class PreparedVariant {
        final CalculatedVariant variant;
        final Map<String, PreparedNode> nodes = new LinkedHashMap<>();
        PreparedVariant(CalculatedVariant variant) { this.variant = variant; }
    }

    static final class PreparedNode {
        final Node node;
        final Point location;
        ChamberCost chamber;
        ObjectId outputId;
        PreparedNode(Node node, Point location) { this.node = node; this.location = location; }
    }
}
