// Android tablet layer for JS Kid Pix.
// Loaded after upstream app.js. Replaces the mouse/touch input with Pointer Events
// so active pens (Lenovo Tab Pen / Tab Pen Plus, USI, MPP, Wacom AES/EMR, S Pen)
// get pressure, hover preview, palm rejection and side-button shortcuts.
(function() {
    'use strict';

    var PALM_GRACE_MS = 800; // ignore finger input this long after the pen was seen
    var PRESSURE_TOOLS = ['Pencil', 'PlainBrush', 'Brush', 'SmoothPen', 'Scribble',
        'Eraser', 'EraserWhiteCircles', 'Fuzzer'
    ];

    var bridge = window.KidPixAndroid || null;
    var state = {
        activeId: null, // pointerId of the stroke in progress
        activeType: null,
        lastPenTs: 0,
        penHovering: false,
        swappedTool: null, // tool to restore after a side-button / eraser-tip stroke
        undoArmed: true,
        lastXY: null
    };
    var penEraser = null;

    function now() {
        return Date.now();
    }

    // ---------- layout: size the drawing canvas to the screen ----------
    function sizeCanvasToScreen() {
        var paint = document.getElementById('paint');
        var canvas = document.getElementById('kiddopaint');
        var rect = paint.getBoundingClientRect();
        var w = Math.max(320, Math.floor(rect.width) - 2);
        var h = Math.max(240, Math.floor(window.innerHeight - rect.top) - 2);
        canvas.width = w;
        canvas.height = h;
    }

    // Splash fits the canvas instead of being cropped at 1024x1024.
    function patchSplash() {
        var orig = KiddoPaint.Display.loadFromLocalStorage;
        KiddoPaint.Display.loadFromLocalStorage = function() {
            if (typeof(Storage) != 'undefined' && localStorage.getItem('kiddopaint')) {
                return orig();
            }
            var img = new Image();
            img.onload = function() {
                var c = KiddoPaint.Display.main_canvas;
                var s = Math.min(c.width / img.width, c.height / img.height);
                var w = img.width * s,
                    h = img.height * s;
                KiddoPaint.Display.clearMain();
                KiddoPaint.Display.main_context.drawImage(img, (c.width - w) / 2, (c.height - h) / 2, w, h);
            };
            img.src = 'static/splash.png';
        };
    }

    // ---------- event plumbing ----------
    function toolName(tool) {
        for (var k in KiddoPaint.Tools) {
            if (KiddoPaint.Tools[k] === tool) return k;
        }
        return '';
    }

    function canvasPoint(canvas, e) {
        var r = canvas.getBoundingClientRect();
        // canvas has a 1px border; offset is from the padding edge
        var cw = r.width - 2,
            ch = r.height - 2;
        // Whole pixels, inside the canvas: flood fill and friends index ImageData with these
        // and loop forever on fractional or out-of-range coordinates.
        var x = Math.round((e.clientX - r.left - 1) * (canvas.width / cw));
        var y = Math.round((e.clientY - r.top - 1) * (canvas.height / ch));
        x = Math.max(0, Math.min(canvas.width - 1, x));
        y = Math.max(0, Math.min(canvas.height - 1, y));
        return {
            x: x,
            y: y
        };
    }

    function fakeEvent(type, canvas, e) {
        var p = canvasPoint(canvas, e);
        return {
            type: type,
            offsetX: p.x,
            offsetY: p.y,
            clientX: e.clientX,
            clientY: e.clientY,
            pageX: e.pageX,
            pageY: e.pageY,
            which: 1,
            button: 0,
            buttons: 1,
            pressure: e.pressure,
            pointerType: e.pointerType,
            tiltX: e.tiltX,
            tiltY: e.tiltY,
            shiftKey: e.shiftKey,
            altKey: e.altKey,
            ctrlKey: e.ctrlKey,
            metaKey: e.metaKey,
            target: canvas,
            srcElement: canvas,
            timeStamp: e.timeStamp,
            preventDefault: function() {},
            stopPropagation: function() {}
        };
    }

    // Pressure -> brush size, only for stroke tools, only for pens.
    function withPressure(e, fn) {
        var tool = KiddoPaint.Current.tool;
        var usePressure = e.pointerType === 'pen' && e.pressure > 0 &&
            PRESSURE_TOOLS.indexOf(toolName(tool)) !== -1;
        if (!usePressure) return fn();
        var base = KiddoPaint.Current.scaling;
        // 0.33 pressure ~ normal size, light = thinner, hard = up to 2x
        KiddoPaint.Current.scaling = base * Math.max(0.35, Math.min(2.0, 0.5 + e.pressure * 1.5));
        try {
            return fn();
        } finally {
            if (!KiddoPaint.Current.velToggle) KiddoPaint.Current.scaling = base;
        }
    }

    function send(type, canvas, e) {
        var fe = fakeEvent(type, canvas, e);
        state.lastXY = {
            clientX: e.clientX,
            clientY: e.clientY,
            pageX: e.pageX,
            pageY: e.pageY,
            pressure: e.pressure,
            pointerType: e.pointerType,
            timeStamp: e.timeStamp
        };
        withPressure(e, function() {
            ev_canvas(fe);
        });
    }

    // Upstream erasers stamp once per event, so fast strokes leave gaps. Fill them in.
    function fillEraserGap(canvas, e, tool) {
        var last = state.lastXY;
        if (!last) return;
        var dx = e.clientX - last.clientX,
            dy = e.clientY - last.clientY;
        var dist = Math.sqrt(dx * dx + dy * dy);
        var step = Math.max(2, (tool.size || 10) * 0.5);
        var n = Math.floor(dist / step);
        for (var i = 1; i < n; i++) {
            var t = i / n;
            send('mousemove', canvas, {
                clientX: last.clientX + dx * t,
                clientY: last.clientY + dy * t,
                pageX: last.clientX + dx * t,
                pageY: last.clientY + dy * t,
                pressure: e.pressure,
                pointerType: e.pointerType,
                timeStamp: e.timeStamp
            });
        }
    }

    function clearOverlays() {
        KiddoPaint.Display.clearPreview();
        KiddoPaint.Display.clearAnim();
        KiddoPaint.Display.clearBnim();
    }

    // ---------- pen side buttons / eraser tip ----------
    // Chromium maps BUTTON_STYLUS_PRIMARY -> buttons&2, BUTTON_STYLUS_SECONDARY -> buttons&4,
    // the eraser end of a pen -> buttons&32.
    function isEraserPen(e) {
        return e.pointerType === 'pen' && ((e.buttons & 32) || (e.buttons & 2));
    }

    function startTempEraser() {
        if (state.swappedTool) return;
        if (!penEraser) {
            penEraser = new KiddoPaint.Tools.Toolbox.Eraser();
            penEraser.size = 14;
            penEraser.isSquareEraser = false;
        }
        state.swappedTool = KiddoPaint.Current.tool;
        KiddoPaint.Current.tool = penEraser;
    }

    function endTempEraser() {
        if (!state.swappedTool) return;
        KiddoPaint.Current.tool = state.swappedTool;
        state.swappedTool = null;
    }

    function penUndoButton(e) {
        if (e.pointerType !== 'pen') return;
        var pressed = (e.buttons & 4) !== 0;
        if (pressed && state.undoArmed) {
            state.undoArmed = false;
            KiddoPaint.Sounds.mainmenu();
            KiddoPaint.Sounds.oops();
            KiddoPaint.Display.undo(!KiddoPaint.Current.modifiedAlt);
        } else if (!pressed) {
            state.undoArmed = true;
        }
    }

    // ---------- listeners ----------
    function palmBlocked(e) {
        if (e.pointerType !== 'touch') return false;
        if (state.activeType === 'pen') return true;
        // hovering pens report moves continuously, so this also covers "pen near the screen"
        return now() - state.lastPenTs < PALM_GRACE_MS;
    }

    function endStroke(canvas, e) {
        var id = state.activeId;
        state.activeId = null;
        state.activeType = null;
        send('mouseup', canvas, e);
        endTempEraser();
        try {
            canvas.releasePointerCapture(id);
        } catch (err) {}
    }

    function installPointerInput(canvas) {
        canvas.style.touchAction = 'none';

        canvas.addEventListener('pointerdown', function(e) {
            e.preventDefault();
            if (e.pointerType === 'pen') {
                state.lastPenTs = now();
                // pen beats finger: end any finger stroke (likely a palm) first
                if (state.activeId !== null && state.activeType !== 'pen') {
                    endStroke(canvas, e);
                }
                penUndoButton(e);
            }
            if (palmBlocked(e)) return;
            // A primary pointer going down means every earlier contact lifted, so a stroke
            // still marked active missed its pointerup. Close it rather than freeze drawing.
            if (state.activeId !== null && (e.isPrimary || e.pointerType === 'pen')) {
                endStroke(canvas, e);
            }
            if (state.activeId !== null) return; // second finger: one stroke at a time
            if (e.pointerType === 'mouse' && e.button !== 0) return;

            state.activeId = e.pointerId;
            state.activeType = e.pointerType;
            try {
                canvas.setPointerCapture(e.pointerId);
            } catch (err) {}
            if (isEraserPen(e)) startTempEraser();
            send('mousedown', canvas, e);
        }, {
            passive: false
        });

        canvas.addEventListener('pointermove', function(e) {
            e.preventDefault();
            if (e.pointerType === 'pen') {
                state.lastPenTs = now();
                penUndoButton(e);
            }
            if (state.activeId === e.pointerId) {
                // Pencil follows every sample the pen reports for smooth lines;
                // other tools keep one dab per frame like the web version.
                var tool = KiddoPaint.Current.tool;
                var isEraser = tool === penEraser || tool === KiddoPaint.Tools.Eraser;
                var list = [e];
                if (e.pointerType === 'pen' && (tool === KiddoPaint.Tools.Pencil || isEraser) &&
                    typeof e.getCoalescedEvents === 'function') {
                    var c = e.getCoalescedEvents();
                    if (c && c.length) list = c;
                }
                for (var i = 0; i < list.length; i++) {
                    if (isEraser) fillEraserGap(canvas, list[i], tool);
                    send('mousemove', canvas, list[i]);
                }
                return;
            }
            // pen hovering while we think it's touching: the pointerup was lost
            if (state.activeType === 'pen' && e.pointerType === 'pen' && e.buttons === 0 &&
                state.activeId !== null) {
                endStroke(canvas, e);
            }
            // hovering pen (or mouse) with nothing pressed: show tool previews like a mouse cursor
            if (state.activeId === null && (e.pointerType === 'pen' || e.pointerType === 'mouse') && e.buttons === 0) {
                if (e.pointerType === 'pen') state.penHovering = true;
                send('mousemove', canvas, e);
            }
        }, {
            passive: false
        });

        function up(e) {
            if (e.pointerType === 'pen') {
                state.lastPenTs = now();
                penUndoButton(e);
            }
            if (state.activeId !== e.pointerId) return;
            endStroke(canvas, e);
        }
        canvas.addEventListener('pointerup', up);
        canvas.addEventListener('pointercancel', up);
        canvas.addEventListener('lostpointercapture', up);

        function leave(e) {
            if (e.pointerType === 'pen') {
                state.penHovering = false;
                state.lastPenTs = now();
            }
            if (state.activeId !== null) return; // captured stroke continues
            if (KiddoPaint.Current.tool && KiddoPaint.Current.tool.isDown) return;
            clearOverlays();
        }
        canvas.addEventListener('pointerleave', leave);
        canvas.addEventListener('pointerout', leave);

        canvas.addEventListener('mousewheel', mouse_wheel);
        canvas.addEventListener('contextmenu', function(e) {
            e.preventDefault();
        });

        // keyboard (Lenovo keyboard covers) keeps upstream shortcuts
        var listenersOnly = document.createElement('canvas');
        upstreamInitListeners(listenersOnly);
    }

    var upstreamInitListeners = window.init_listeners;
    window.init_listeners = installPointerInput;

    // Pens tap the toolbar too: make sure finger palms don't trigger tool switches while drawing.
    function guardToolbarFromPalms() {
        ['toolbar', 'subtoolbars'].forEach(function(id) {
            var el = document.getElementById(id);
            if (!el) return;
            ['pointerdown', 'mousedown', 'click'].forEach(function(type) {
                el.addEventListener(type, function(e) {
                    var t = e.pointerType || (e.sourceCapabilities && e.sourceCapabilities.firesTouchEvents ? 'touch' : '');
                    if (state.activeType === 'pen' && t !== 'pen' && now() - state.lastPenTs < 1000) {
                        e.stopImmediatePropagation();
                        e.preventDefault();
                    }
                }, true);
            });
        });
    }

    // ---------- save to the Android gallery ----------
    var upstreamSave = window.save_to_file;
    window.save_to_file = function() {
        if (!bridge || !bridge.savePng) return upstreamSave();
        var canvasToSave = KiddoPaint.Current.modifiedAlt ?
            trimAndFlattenCanvas(KiddoPaint.Display.main_canvas) : KiddoPaint.Display.main_canvas;
        var url = canvasToSave.toDataURL('image/png');
        bridge.savePng(url.substring(url.indexOf(',') + 1));
    };

    // keep the drawing if Android kills the app in the background
    document.addEventListener('visibilitychange', function() {
        if (document.hidden && KiddoPaint.Display && KiddoPaint.Display.main_canvas) {
            KiddoPaint.Display.saveToLocalStorage();
        }
    });

    window.kidpix_android_boot = function() {
        document.documentElement.classList.add('kidpix-android');
        sizeCanvasToScreen();
        patchSplash();
        init_kiddo_paint();
        guardToolbarFromPalms();
    };

    // test hook: lets instrumentation read state without poking internals
    window.__kidpixAndroid = {
        state: state,
        toolName: function() {
            return toolName(KiddoPaint.Current.tool);
        }
    };
})();
