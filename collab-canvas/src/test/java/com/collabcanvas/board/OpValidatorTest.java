package com.collabcanvas.board;

import com.collabcanvas.board.OpValidator.InvalidMessage;
import com.collabcanvas.model.Op;
import com.collabcanvas.model.ShapeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s.replace('\'', '"'));
    }

    private static final String RECT =
            "{'id':'u1-1','type':'RECT','x':1,'y':2,'w':30,'h':40,'stroke':'#AABBCC','fill':'none','width':2,'z':'a0'}";

    private String add(String shape) {
        return "{'opId':'u1-9','kind':'add','shape':" + shape + "}";
    }

    @Test
    void parsesValidAddAndNormalizesColor() throws Exception {
        Op op = OpValidator.parseOp(json(add(RECT)));
        Op.Add a = assertInstanceOf(Op.Add.class, op);
        assertEquals(ShapeType.RECT, a.shape().type());
        assertEquals("#aabbcc", a.shape().stroke());
        assertNull(a.shape().author());
    }

    @Test
    void parsesStrokeUpdateAndDelete() throws Exception {
        String stroke = "{'id':'u1-2','type':'STROKE','x':0,'y':0,'w':0,'h':0,'points':[0,0,1,1],"
                + "'stroke':'#000000','fill':'none','width':3,'z':'a1'}";
        assertInstanceOf(Op.Add.class, OpValidator.parseOp(json(add(stroke))));
        assertInstanceOf(Op.Update.class,
                OpValidator.parseOp(json("{'opId':'u1-3','kind':'update','id':'u1-2','set':{'x':5}}")));
        assertInstanceOf(Op.Delete.class,
                OpValidator.parseOp(json("{'opId':'u1-4','kind':'delete','id':'u1-2'}")));
    }

    private void assertRejected(String reason, String body) {
        InvalidMessage e = assertThrows(InvalidMessage.class, () -> OpValidator.parseOp(json(body)));
        assertEquals(reason, e.getMessage());
    }

    @Test
    void rejectsClientSuppliedServerFields() {
        assertRejected("unknown_field", add(RECT.replace("}", ",'author':'someone-else'}")));
        assertRejected("unknown_field", add(RECT.replace("}", ",'version':99}")));
        assertRejected("unknown_field",
                "{'opId':'u1-3','kind':'update','id':'u1-2','set':{'author':'x'}}");
        assertRejected("unknown_field", "{'opId':'u1-4','kind':'delete','id':'u1-2','extra':1}");
    }

    @Test
    void rejectsBadIdsColorsTypesAndRanges() {
        assertRejected("invalid_id", add(RECT.replace("u1-1", "<script>")));
        assertRejected("invalid_opId", "{'opId':'a b','kind':'delete','id':'u1-2'}");
        assertRejected("invalid_stroke", add(RECT.replace("#AABBCC", "red")));
        assertRejected("invalid_fill", add(RECT.replace("'none'", "'url(x)'")));
        assertRejected("invalid_type", add(RECT.replace("RECT", "IMAGE")));
        assertRejected("invalid_x", add(RECT.replace("'x':1", "'x':1e9")));
        assertRejected("invalid_x", add(RECT.replace("'x':1", "'x':'1'")));
        assertRejected("invalid_width", add(RECT.replace("'width':2", "'width':0")));
        assertRejected("invalid_z", add(RECT.replace("'a0'", "'a-0'")));
        assertRejected("invalid_kind", "{'opId':'u1-4','kind':'drop','id':'u1-2'}");
        assertRejected("empty_update", "{'opId':'u1-3','kind':'update','id':'u1-2','set':{}}");
    }

    @Test
    void rejectsBadPoints() {
        String base = "{'id':'u1-2','type':'STROKE','x':0,'y':0,'w':0,'h':0,'points':PTS,"
                + "'stroke':'#000000','fill':'none','width':3,'z':'a1'}";
        assertRejected("invalid_points", add(base.replace("PTS", "[1,2,3]")));
        assertRejected("invalid_points", add(base.replace("PTS", "[]")));
        assertRejected("invalid_points", add(base.replace("PTS", "[1,'x']")));
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i < OpValidator.MAX_POINT_COORDS + 2; i++) {
            big.append(i == 0 ? "" : ",").append('1');
        }
        assertRejected("invalid_points", add(base.replace("PTS", big.append(']'))));
        assertRejected("points_mismatch", add(RECT.replace("'z':'a0'", "'z':'a0','points':[1,1]")));
        assertRejected("points_mismatch", add(base.replace(",'points':PTS", "")));
    }

    @Test
    void opCountIsBounded() throws Exception {
        assertThrows(InvalidMessage.class, () -> OpValidator.checkOpCount(json("[]")));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i <= OpValidator.MAX_OPS_PER_MESSAGE; i++) {
            many.append(i == 0 ? "" : ",").append("{}");
        }
        assertThrows(InvalidMessage.class, () -> OpValidator.checkOpCount(json(many + "]")));
    }

    @Test
    void parsesPreviewAndCursor() throws Exception {
        OpValidator.Preview p = OpValidator.parsePreview(
                json("{'t':'preview','sid':'u1-5','pts':[1,2,3,4],'stroke':'#112233','width':4,'done':false}"));
        assertEquals(4, p.pts().length);
        OpValidator.Cursor c = OpValidator.parseCursor(json("{'t':'cursor','x':10,'y':20}"));
        assertEquals(20, c.y());
        assertThrows(InvalidMessage.class, () -> OpValidator.parseCursor(json("{'t':'cursor','x':10,'y':20,'name':'x'}")));
        assertThrows(InvalidMessage.class, () -> OpValidator.parsePreview(
                json("{'t':'preview','sid':'u1-5','pts':[1,2],'stroke':'#112233','width':4}")));
    }
}
