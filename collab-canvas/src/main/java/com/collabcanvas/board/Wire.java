package com.collabcanvas.board;

import com.collabcanvas.model.Op;
import com.collabcanvas.model.Shape;
import com.collabcanvas.model.ShapePatch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;

/** Server -> client JSON messages. Built field by field so the wire format is explicit. */
public final class Wire {

    private final ObjectMapper mapper;

    public Wire(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public ObjectNode shape(Shape s) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", s.id());
        n.put("type", s.type().name());
        n.put("x", s.x());
        n.put("y", s.y());
        n.put("w", s.w());
        n.put("h", s.h());
        if (s.points() != null) {
            ArrayNode pts = n.putArray("points");
            for (double d : s.points()) {
                pts.add(d);
            }
        }
        n.put("stroke", s.stroke());
        n.put("fill", s.fill());
        n.put("width", s.width());
        n.put("z", s.z());
        n.put("author", s.author());
        n.put("version", s.version());
        return n;
    }

    public ObjectNode patch(ShapePatch p) {
        ObjectNode n = mapper.createObjectNode();
        if (p.x() != null) n.put("x", p.x());
        if (p.y() != null) n.put("y", p.y());
        if (p.w() != null) n.put("w", p.w());
        if (p.h() != null) n.put("h", p.h());
        if (p.points() != null) {
            ArrayNode pts = n.putArray("points");
            for (double d : p.points()) {
                pts.add(d);
            }
        }
        if (p.stroke() != null) n.put("stroke", p.stroke());
        if (p.fill() != null) n.put("fill", p.fill());
        if (p.width() != null) n.put("width", p.width());
        if (p.z() != null) n.put("z", p.z());
        return n;
    }

    public ObjectNode peer(Peer p) {
        ObjectNode n = mapper.createObjectNode();
        n.put("userId", p.userId());
        n.put("name", p.name());
        n.put("color", p.color());
        return n;
    }

    public String welcome(String boardId, Peer you, long seq, Collection<Shape> shapes, Collection<Peer> peers) {
        ObjectNode n = mapper.createObjectNode();
        n.put("t", "welcome");
        n.put("boardId", boardId);
        n.set("you", peer(you));
        n.put("seq", seq);
        ArrayNode arr = n.putArray("shapes");
        shapes.forEach(s -> arr.add(shape(s)));
        ArrayNode ps = n.putArray("peers");
        peers.forEach(p -> ps.add(peer(p)));
        return write(n);
    }

    /** {@code committed} carries the op as applied (an add includes the server-set author/version). */
    public String committed(long seq, Op op, String author, Shape result) {
        ObjectNode n = mapper.createObjectNode();
        n.put("t", "committed");
        n.put("seq", seq);
        n.put("opId", op.opId());
        n.put("author", author);
        ObjectNode o = n.putObject("op");
        switch (op) {
            case Op.Add a -> {
                o.put("kind", "add");
                o.set("shape", shape(result));
            }
            case Op.Update u -> {
                o.put("kind", "update");
                o.put("id", u.shapeId());
                o.set("set", patch(u.set()));
                o.put("version", seq);
            }
            case Op.Delete d -> {
                o.put("kind", "delete");
                o.put("id", d.shapeId());
            }
        }
        return write(n);
    }

    public String rejected(String opId, String reason) {
        ObjectNode n = mapper.createObjectNode();
        n.put("t", "rejected");
        n.put("opId", opId);
        n.put("reason", reason);
        return write(n);
    }

    public String error(String reason) {
        ObjectNode n = mapper.createObjectNode();
        n.put("t", "error");
        n.put("reason", reason);
        return write(n);
    }

    public String presence(Collection<Peer> peers) {
        ObjectNode n = mapper.createObjectNode();
        n.put("t", "presence");
        ArrayNode ps = n.putArray("peers");
        peers.forEach(p -> ps.add(peer(p)));
        return write(n);
    }

    public String preview(String author, OpValidator.Preview p) {
        ObjectNode n = mapper.createObjectNode();
        n.put("t", "preview");
        n.put("author", author);
        n.put("sid", p.sid());
        ArrayNode pts = n.putArray("pts");
        for (double d : p.pts()) {
            pts.add(d);
        }
        n.put("stroke", p.stroke());
        n.put("width", p.width());
        n.put("done", p.done());
        return write(n);
    }

    public String cursor(String author, OpValidator.Cursor c) {
        ObjectNode n = mapper.createObjectNode();
        n.put("t", "cursor");
        n.put("author", author);
        n.put("x", c.x());
        n.put("y", c.y());
        return write(n);
    }

    private String write(ObjectNode n) {
        try {
            return mapper.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialization failed", e);
        }
    }
}
