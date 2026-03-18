package com.botwithus.bot.core.blueprint.serialization;

import com.botwithus.bot.api.blueprint.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generates example blueprint JSON files.
 * Run {@link #main} to write them to {@code scripts/blueprints/}.
 */
public final class ExampleBlueprints {

    private static final Logger log = LoggerFactory.getLogger(ExampleBlueprints.class);

    private ExampleBlueprints() {}

    // =================== Pin index reference ===================
    // Pin ID = nodeId * 1000 + pinIndex (0-based position in definition's pin list)
    //
    // flow.onStart:  [0]=exec_out
    // flow.onLoop:   [0]=exec_out  [1]=delay
    // flow.onStop:   [0]=exec_out
    // flow.branch:   [0]=exec_in  [1]=condition  [2]=true  [3]=false
    // flow.delay:    [0]=exec_in  [1]=milliseconds  [2]=exec_out
    // debug.print:   [0]=exec_in  [1]=value  [2]=label  [3]=exec_out
    // data.const_int:    [0]=value   (property: value)
    // data.const_string: [0]=value   (property: value)
    // data.const_bool:   [0]=value   (property: value)
    // data.get_variable: [0]=value   (property: name)
    // data.set_variable: [0]=exec_in  [1]=value  [2]=exec_out  (property: name)
    // data.list_size:    [0]=list  [1]=size
    // data.list_isEmpty: [0]=list  [1]=empty
    // logic.compare:     [0]=a  [1]=b  [2]=result  (property: operator)
    // logic.not:         [0]=input  [1]=result
    //
    // gameapi.getLocalPlayer: [0]=serverIndex [1]=name [2]=tileX [3]=tileY [4]=plane
    //   [5]=isMember [6]=isMoving [7]=animationId [8]=stanceId [9]=health [10]=maxHealth
    //   [11]=combatLevel [12]=overheadText [13]=targetIndex [14]=targetType
    //
    // gameapi.queryInventoryItems: [0]=inventoryId [1]=itemId [2]=items
    //
    // gameapi.queryEntities: [0]=type [1]=namePattern [2]=typeId [3]=radius [4]=tileX
    //   [5]=tileY [6]=visibleOnly [7]=inCombat [8]=notInCombat [9]=sortByDistance
    //   [10]=maxResults [11]=entities
    //
    // gameapi.queueAction: [0]=exec_in [1]=actionId [2]=param1 [3]=param2 [4]=param3 [5]=exec_out

    /**
     * Builds the Woodcutting Fletcher blueprint — a state machine that chops trees
     * when inventory has space, and fletches logs when inventory is full.
     * <p>
     * Equivalent to {@code WoodcuttingFletcherScript.java} in the example-script module.
     */
    public static BlueprintGraph buildWoodcuttingFletcher() {
        BlueprintGraph g = new BlueprintGraph(new BlueprintMetadata(
                "Woodcutting Fletcher", "1.0", "BotWithUs",
                "Chops trees and fletches logs into arrow shafts"
        ));
        g.getVariables().put("state", PinType.INT); // 0=CHOPPING, 1=FLETCHING

        // ═══════════════════════════════════════════════════════════════
        //  ON START: print → set state = 0
        // ═══════════════════════════════════════════════════════════════
        NodeInstance onStart = node(g, 1, "flow.onStart", 50, 300);
        NodeInstance startPrint = node(g, 2, "debug.print", 250, 300);
        NodeInstance startMsg = node(g, 3, "data.const_string", 100, 400);
        startMsg.setProperty("value", "Woodcutting Fletcher Started!");
        NodeInstance setState0 = node(g, 4, "data.set_variable", 500, 300);
        setState0.setProperty("name", "state");
        NodeInstance zero = node(g, 5, "data.const_int", 350, 400);
        zero.setProperty("value", 0);

        link(g, 101, pin(1,0), pin(2,0));  // onStart:exec_out → print:exec_in
        link(g, 102, pin(3,0), pin(2,1));  // msg:value → print:value
        link(g, 103, pin(2,3), pin(4,0));  // print:exec_out → setState:exec_in
        link(g, 104, pin(5,0), pin(4,1));  // zero:value → setState:value

        // ═══════════════════════════════════════════════════════════════
        //  ON STOP: print
        // ═══════════════════════════════════════════════════════════════
        NodeInstance onStop = node(g, 6, "flow.onStop", 50, 600);
        NodeInstance stopPrint = node(g, 7, "debug.print", 250, 600);
        NodeInstance stopMsg = node(g, 8, "data.const_string", 100, 700);
        stopMsg.setProperty("value", "Woodcutting Fletcher Stopped.");

        link(g, 105, pin(6,0), pin(7,0));  // onStop:exec_out → print:exec_in
        link(g, 106, pin(8,0), pin(7,1));  // msg:value → print:value

        // ═══════════════════════════════════════════════════════════════
        //  ON LOOP: state dispatch
        //  get_variable("state") → compare(== 0) → branch
        // ═══════════════════════════════════════════════════════════════
        NodeInstance onLoop = node(g, 10, "flow.onLoop", 50, 1000);
        NodeInstance getState = node(g, 11, "data.get_variable", 200, 1100);
        getState.setProperty("name", "state");
        NodeInstance cmpState = node(g, 12, "logic.compare", 400, 1100);
        cmpState.setProperty("operator", "==");
        NodeInstance zeroConst = node(g, 13, "data.const_int", 300, 1200);
        zeroConst.setProperty("value", 0);
        NodeInstance stateBranch = node(g, 14, "flow.branch", 600, 1000);

        link(g, 200, pin(10,0), pin(14,0));  // onLoop:exec_out → branch:exec_in
        link(g, 201, pin(11,0), pin(12,0));  // getState:value → compare:a
        link(g, 202, pin(13,0), pin(12,1));  // zero:value → compare:b
        link(g, 203, pin(12,2), pin(14,1));  // compare:result → branch:condition

        // ═══════════════════════════════════════════════════════════════
        //  CHOPPING STATE (branch TRUE → pin 14002)
        //  Step 1: query backpack items (inventoryId=93) → list_size → compare >= 28
        // ═══════════════════════════════════════════════════════════════
        NodeInstance queryBP = node(g, 20, "gameapi.queryInventoryItems", 900, 800);
        NodeInstance inv93 = node(g, 21, "data.const_int", 750, 900);
        inv93.setProperty("value", 93);
        NodeInstance bpSize = node(g, 22, "data.list_size", 1150, 800);
        NodeInstance cmpFull = node(g, 23, "logic.compare", 1350, 800);
        cmpFull.setProperty("operator", ">=");
        NodeInstance twentyEight = node(g, 24, "data.const_int", 1200, 900);
        twentyEight.setProperty("value", 28);
        NodeInstance fullBranch = node(g, 25, "flow.branch", 1550, 800);

        link(g, 300, pin(21,0), pin(20,0));   // 93 → queryInv:inventoryId
        link(g, 301, pin(20,2), pin(22,0));   // queryInv:items → listSize:list
        link(g, 302, pin(22,1), pin(23,0));   // listSize:size → compare:a
        link(g, 303, pin(24,0), pin(23,1));   // 28 → compare:b
        link(g, 304, pin(23,2), pin(25,1));   // compare:result → branch:condition
        link(g, 305, pin(14,2), pin(25,0));   // stateBranch:true → fullBranch:exec_in

        // ── Inventory FULL (25:true) → print, set state=1, delay 300 ──
        NodeInstance fullPrint = node(g, 26, "debug.print", 1800, 650);
        NodeInstance fullMsg = node(g, 27, "data.const_string", 1650, 550);
        fullMsg.setProperty("value", "Inventory full, switching to fletching");
        NodeInstance setFletching = node(g, 28, "data.set_variable", 2100, 650);
        setFletching.setProperty("name", "state");
        NodeInstance one = node(g, 29, "data.const_int", 1950, 750);
        one.setProperty("value", 1);
        NodeInstance delay300a = node(g, 30, "flow.delay", 2350, 650);
        NodeInstance d300a = node(g, 31, "data.const_int", 2200, 750);
        d300a.setProperty("value", 300);

        link(g, 310, pin(25,2), pin(26,0));   // fullBranch:true → print:exec_in
        link(g, 311, pin(27,0), pin(26,1));   // msg → print:value
        link(g, 312, pin(26,3), pin(28,0));   // print:exec_out → setState:exec_in
        link(g, 313, pin(29,0), pin(28,1));   // 1 → setState:value
        link(g, 314, pin(28,2), pin(30,0));   // setState:exec_out → delay:exec_in
        link(g, 315, pin(31,0), pin(30,1));   // 300 → delay:milliseconds

        // ── Inventory NOT full (25:false) → check if animating ──
        NodeInstance getLP1 = node(g, 32, "gameapi.getLocalPlayer", 1200, 1100);
        NodeInstance cmpAnim1 = node(g, 33, "logic.compare", 1500, 1100);
        cmpAnim1.setProperty("operator", "!=");
        NodeInstance negOne1 = node(g, 34, "data.const_int", 1350, 1200);
        negOne1.setProperty("value", -1);
        NodeInstance animBranch1 = node(g, 35, "flow.branch", 1700, 1000);

        link(g, 320, pin(25,3), pin(35,0));   // fullBranch:false → animBranch:exec_in
        link(g, 321, pin(32,7), pin(33,0));   // getLP:animationId → compare:a
        link(g, 322, pin(34,0), pin(33,1));   // -1 → compare:b
        link(g, 323, pin(33,2), pin(35,1));   // compare:result → branch:condition

        // ── Animating (35:true) → delay 600 ──
        NodeInstance delay600a = node(g, 36, "flow.delay", 1950, 950);
        NodeInstance d600a = node(g, 37, "data.const_int", 1800, 1050);
        d600a.setProperty("value", 600);

        link(g, 330, pin(35,2), pin(36,0));   // animBranch:true → delay:exec_in
        link(g, 331, pin(37,0), pin(36,1));   // 600 → delay:ms

        // ── Idle (35:false) → query for trees ──
        NodeInstance queryTree = node(g, 38, "gameapi.queryEntities", 1950, 1200);
        NodeInstance typeStr = node(g, 39, "data.const_string", 1800, 1350);
        typeStr.setProperty("value", "location");
        NodeInstance nameStr = node(g, 40, "data.const_string", 1800, 1450);
        nameStr.setProperty("value", "Tree");
        NodeInstance sortTrue = node(g, 41, "data.const_bool", 1800, 1550);
        sortTrue.setProperty("value", true);
        NodeInstance maxRes1 = node(g, 42, "data.const_int", 1800, 1650);
        maxRes1.setProperty("value", 1);

        link(g, 340, pin(39,0), pin(38,0));   // "location" → query:type
        link(g, 341, pin(40,0), pin(38,1));   // "Tree" → query:namePattern
        link(g, 342, pin(32,2), pin(38,4));   // getLP:tileX → query:tileX
        link(g, 343, pin(32,3), pin(38,5));   // getLP:tileY → query:tileY
        link(g, 344, pin(41,0), pin(38,9));   // true → query:sortByDistance
        link(g, 345, pin(42,0), pin(38,10));  // 1 → query:maxResults

        // Check if tree list empty
        NodeInstance treeEmpty = node(g, 43, "data.list_isEmpty", 2250, 1200);
        NodeInstance treeBranch = node(g, 44, "flow.branch", 2450, 1100);

        link(g, 350, pin(38,11), pin(43,0));  // query:entities → isEmpty:list
        link(g, 351, pin(43,1), pin(44,1));   // isEmpty:empty → branch:condition
        link(g, 352, pin(35,3), pin(44,0));   // animBranch:false → treeBranch:exec_in

        // ── No tree found (44:true=empty) → print, delay 600 ──
        NodeInstance noTreePrint = node(g, 45, "debug.print", 2700, 1000);
        NodeInstance noTreeMsg = node(g, 46, "data.const_string", 2550, 900);
        noTreeMsg.setProperty("value", "No tree found!");
        NodeInstance delay600b = node(g, 47, "flow.delay", 2950, 1000);
        NodeInstance d600b = node(g, 48, "data.const_int", 2800, 1100);
        d600b.setProperty("value", 600);

        link(g, 360, pin(44,2), pin(45,0));   // treeBranch:true(empty) → print:exec_in
        link(g, 361, pin(46,0), pin(45,1));   // msg → print:value
        link(g, 362, pin(45,3), pin(47,0));   // print:exec_out → delay:exec_in
        link(g, 363, pin(48,0), pin(47,1));   // 600 → delay:ms

        // ── Tree found (44:false=not empty) → queueAction(OBJECT1=3), delay 1200 ──
        NodeInstance chopPrint = node(g, 49, "debug.print", 2700, 1250);
        NodeInstance chopMsg = node(g, 50, "data.const_string", 2550, 1350);
        chopMsg.setProperty("value", "Chopping tree...");
        NodeInstance chopAction = node(g, 51, "gameapi.queueAction", 2950, 1250);
        NodeInstance actId3 = node(g, 52, "data.const_int", 2800, 1400);
        actId3.setProperty("value", 3); // OBJECT1 = "Chop down"
        NodeInstance delay1200a = node(g, 53, "flow.delay", 3200, 1250);
        NodeInstance d1200a = node(g, 54, "data.const_int", 3050, 1350);
        d1200a.setProperty("value", 1200);

        link(g, 370, pin(44,3), pin(49,0));   // treeBranch:false(found) → print:exec_in
        link(g, 371, pin(50,0), pin(49,1));   // msg → print:value
        link(g, 372, pin(49,3), pin(51,0));   // print:exec_out → queueAction:exec_in
        link(g, 373, pin(52,0), pin(51,1));   // 3 → queueAction:actionId
        link(g, 374, pin(51,5), pin(53,0));   // queueAction:exec_out → delay:exec_in
        link(g, 375, pin(54,0), pin(53,1));   // 1200 → delay:ms

        // ═══════════════════════════════════════════════════════════════
        //  FLETCHING STATE (branch FALSE → pin 14003)
        //  Step 1: query backpack for logs (inventoryId=93, itemId=1511)
        //          → list_isEmpty → branch
        // ═══════════════════════════════════════════════════════════════
        NodeInstance queryLogs = node(g, 60, "gameapi.queryInventoryItems", 900, 1600);
        NodeInstance inv93b = node(g, 61, "data.const_int", 750, 1700);
        inv93b.setProperty("value", 93);
        NodeInstance logId = node(g, 62, "data.const_int", 750, 1800);
        logId.setProperty("value", 1511); // Logs item ID
        NodeInstance logsEmpty = node(g, 63, "data.list_isEmpty", 1150, 1600);
        NodeInstance logsBranch = node(g, 64, "flow.branch", 1350, 1500);

        link(g, 400, pin(61,0), pin(60,0));   // 93 → queryInv:inventoryId
        link(g, 401, pin(62,0), pin(60,1));   // 1511 → queryInv:itemId
        link(g, 402, pin(60,2), pin(63,0));   // queryInv:items → isEmpty:list
        link(g, 403, pin(63,1), pin(64,1));   // isEmpty:empty → branch:condition
        link(g, 404, pin(14,3), pin(64,0));   // stateBranch:false → logsBranch:exec_in

        // ── No logs (64:true=empty) → print, set state=0, delay 300 ──
        NodeInstance noLogsPrint = node(g, 65, "debug.print", 1600, 1400);
        NodeInstance noLogsMsg = node(g, 66, "data.const_string", 1450, 1300);
        noLogsMsg.setProperty("value", "No logs remaining, switching to chopping");
        NodeInstance setChopping = node(g, 67, "data.set_variable", 1850, 1400);
        setChopping.setProperty("name", "state");
        NodeInstance zeroB = node(g, 68, "data.const_int", 1700, 1500);
        zeroB.setProperty("value", 0);
        NodeInstance delay300b = node(g, 69, "flow.delay", 2100, 1400);
        NodeInstance d300b = node(g, 70, "data.const_int", 1950, 1500);
        d300b.setProperty("value", 300);

        link(g, 410, pin(64,2), pin(65,0));   // logsBranch:true(empty) → print:exec_in
        link(g, 411, pin(66,0), pin(65,1));   // msg → print:value
        link(g, 412, pin(65,3), pin(67,0));   // print:exec_out → setState:exec_in
        link(g, 413, pin(68,0), pin(67,1));   // 0 → setState:value
        link(g, 414, pin(67,2), pin(69,0));   // setState:exec_out → delay:exec_in
        link(g, 415, pin(70,0), pin(69,1));   // 300 → delay:ms

        // ── Has logs (64:false) → check if animating ──
        NodeInstance getLP2 = node(g, 71, "gameapi.getLocalPlayer", 1200, 1800);
        NodeInstance cmpAnim2 = node(g, 72, "logic.compare", 1500, 1800);
        cmpAnim2.setProperty("operator", "!=");
        NodeInstance negOne2 = node(g, 73, "data.const_int", 1350, 1900);
        negOne2.setProperty("value", -1);
        NodeInstance animBranch2 = node(g, 74, "flow.branch", 1700, 1700);

        link(g, 420, pin(64,3), pin(74,0));   // logsBranch:false → animBranch:exec_in
        link(g, 421, pin(71,7), pin(72,0));   // getLP:animationId → compare:a
        link(g, 422, pin(73,0), pin(72,1));   // -1 → compare:b
        link(g, 423, pin(72,2), pin(74,1));   // compare:result → branch:condition

        // ── Animating (74:true) → delay 600 ──
        NodeInstance delay600c = node(g, 75, "flow.delay", 1950, 1650);
        NodeInstance d600c = node(g, 76, "data.const_int", 1800, 1750);
        d600c.setProperty("value", 600);

        link(g, 430, pin(74,2), pin(75,0));   // animBranch:true → delay:exec_in
        link(g, 431, pin(76,0), pin(75,1));   // 600 → delay:ms

        // ── Idle (74:false) → fletch logs: queueAction(57=COMPONENT, 1, -1, hash), delay 1200 ──
        //    hash = PRODUCTION_INTERFACE(1370) << 16 | ARROW_SHAFT_COMPONENT(14) = 89800718
        //    But first we use a simpler approach: just click fletch on logs in backpack.
        //    The actual interaction is queueAction with COMPONENT action type.
        //    For this example we use the component hash for the backpack item action.
        //    Backpack hash = INTERFACE(1473) << 16 | COMPONENT(5) = 96534533
        NodeInstance fletchPrint = node(g, 77, "debug.print", 1950, 1850);
        NodeInstance fletchMsg = node(g, 78, "data.const_string", 1800, 1950);
        fletchMsg.setProperty("value", "Fletching logs...");
        NodeInstance fletchAction = node(g, 79, "gameapi.queueAction", 2200, 1850);
        NodeInstance actComp = node(g, 80, "data.const_int", 2050, 2000);
        actComp.setProperty("value", 57);  // ActionTypes.COMPONENT
        NodeInstance param1_1 = node(g, 81, "data.const_int", 2050, 2100);
        param1_1.setProperty("value", 1);  // option index
        NodeInstance param2_neg1 = node(g, 82, "data.const_int", 2050, 2200);
        param2_neg1.setProperty("value", -1);  // sub-component
        NodeInstance param3_hash = node(g, 83, "data.const_int", 2050, 2300);
        param3_hash.setProperty("value", 96534533);  // (1473 << 16) | 5 = backpack component hash
        NodeInstance delay1200b = node(g, 84, "flow.delay", 2450, 1850);
        NodeInstance d1200b = node(g, 85, "data.const_int", 2300, 1950);
        d1200b.setProperty("value", 1200);

        link(g, 440, pin(74,3), pin(77,0));   // animBranch:false → print:exec_in
        link(g, 441, pin(78,0), pin(77,1));   // msg → print:value
        link(g, 442, pin(77,3), pin(79,0));   // print:exec_out → queueAction:exec_in
        link(g, 443, pin(80,0), pin(79,1));   // 57 → actionId
        link(g, 444, pin(81,0), pin(79,2));   // 1 → param1
        link(g, 445, pin(82,0), pin(79,3));   // -1 → param2
        link(g, 446, pin(83,0), pin(79,4));   // hash → param3
        link(g, 447, pin(79,5), pin(84,0));   // queueAction:exec_out → delay:exec_in
        link(g, 448, pin(85,0), pin(84,1));   // 1200 → delay:ms

        // Set nextId past all allocated IDs
        g.setNextId(500);

        return g;
    }

    // =================== Helpers ===================

    private static NodeInstance node(BlueprintGraph g, long id, String typeId, float x, float y) {
        NodeInstance n = new NodeInstance(id, typeId, x, y);
        g.getNodes().add(n);
        return n;
    }

    private static long pin(long nodeId, int pinIndex) {
        return nodeId * 1000 + pinIndex;
    }

    private static void link(BlueprintGraph g, long id, long srcPin, long dstPin) {
        g.getLinks().add(new Link(id, srcPin, dstPin));
    }

    /**
     * Generates example blueprint JSON files into {@code scripts/blueprints/}.
     */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of("scripts", "blueprints");
        java.nio.file.Files.createDirectories(dir);

        BlueprintGraph fletcher = buildWoodcuttingFletcher();
        Path path = dir.resolve("woodcutting-fletcher.blueprint.json");
        BlueprintSerializer.saveToFile(fletcher, path);
        log.info("Saved: {}", path.toAbsolutePath());

        // Verify round-trip
        BlueprintGraph reloaded = BlueprintSerializer.loadFromFile(path);
        log.info("Reloaded: {} — {} nodes, {} links", reloaded.getMetadata().name(),
                reloaded.getNodes().size(), reloaded.getLinks().size());
    }
}
