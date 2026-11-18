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

    // Our own opening page instead of the JS Kid Pix splash, fit to the canvas.
    function patchSplash() {
        var orig = KiddoPaint.Display.loadFromLocalStorage;
        KiddoPaint.Display.loadFromLocalStorage = function() {
            if (typeof(Storage) != 'undefined' && localStorage.getItem('kiddopaint')) {
                return orig();
            }
            var img = new Image();
            img.onload = function() {
                var c = KiddoPaint.Display.main_canvas;
                var ctx = KiddoPaint.Display.main_context;
                var s = Math.min(c.width / img.width, c.height / img.height); // white art edges blend into the page
                var w = img.width * s,
                    h = img.height * s;
                KiddoPaint.Display.clearMain();
                ctx.imageSmoothingEnabled = true;
                ctx.imageSmoothingQuality = 'high';
                ctx.drawImage(img, (c.width - w) / 2, (c.height - h) / 2, w, h);
                ctx.imageSmoothingEnabled = false;
            };
            img.src = 'android/opening.png';
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
            undoGuyTap();
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
        // white paper behind the drawing, so it looks right in the gallery and the picker
        var flat = document.createElement('canvas');
        flat.width = canvasToSave.width;
        flat.height = canvasToSave.height;
        var fctx = flat.getContext('2d');
        fctx.fillStyle = '#fff';
        fctx.fillRect(0, 0, flat.width, flat.height);
        fctx.drawImage(canvasToSave, 0, 0);
        var url = flat.toDataURL('image/png');
        bridge.savePng(url.substring(url.indexOf(',') + 1));
    };

    // keep the drawing if Android kills the app in the background
    document.addEventListener('visibilitychange', function() {
        if (document.hidden && KiddoPaint.Display && KiddoPaint.Display.main_canvas) {
            KiddoPaint.Display.saveToLocalStorage();
        }
    });


    // ---------- open a saved picture ----------
    // The app serves Pictures/Kid Pix at /pictures/ (see Pictures.kt). Wordless picker:
    // a grid of thumbnails, tap one to open it, X to close. Opening is undoable.
    function loadPicture(id) {
        var img = new Image();
        img.onload = function() {
            var c = KiddoPaint.Display.main_canvas;
            var ctx = KiddoPaint.Display.main_context;
            KiddoPaint.Display.saveUndo();
            KiddoPaint.Display.clearMain();
            if (img.width === c.width && img.height === c.height) {
                ctx.drawImage(img, 0, 0);
            } else {
                var s = Math.min(c.width / img.width, c.height / img.height);
                var w = img.width * s,
                    h = img.height * s;
                ctx.imageSmoothingEnabled = true;
                ctx.drawImage(img, (c.width - w) / 2, (c.height - h) / 2, w, h);
                ctx.imageSmoothingEnabled = false;
            }
            KiddoPaint.Display.saveToLocalStorage();
            KiddoPaint.Sounds.stamp();
        };
        img.src = '/pictures/full/' + id + '?t=' + Date.now();
    }

    function closePicker() {
        var p = document.getElementById('kp-picker');
        if (p) p.parentNode.removeChild(p);
    }

    function openPicker() {
        closePicker();
        var p = document.createElement('div');
        p.id = 'kp-picker';
        var close = document.createElement('button');
        close.className = 'kp-close';
        close.textContent = '✕';
        close.addEventListener('click', function() {
            KiddoPaint.Sounds.submenuoption();
            closePicker();
        });
        p.appendChild(close);
        var grid = document.createElement('div');
        grid.className = 'kp-grid';
        p.appendChild(grid);
        document.body.appendChild(p);

        fetch('/pictures/list', {
            cache: 'no-store'
        }).then(function(r) {
            return r.json();
        }).then(function(list) {
            if (!list.length) {
                p.classList.add('kp-empty');
                var e = document.createElement('img');
                e.src = 'android/open.png';
                e.className = 'kp-empty-icon pixelated';
                grid.appendChild(e);
                return;
            }
            list.forEach(function(pic) {
                var b = document.createElement('button');
                b.className = 'kp-thumb';
                b.dataset.id = pic.id;
                var im = document.createElement('img');
                im.src = '/pictures/thumb/' + pic.id;
                im.setAttribute('draggable', 'false');
                b.appendChild(im);
                b.addEventListener('click', function() {
                    closePicker();
                    loadPicture(pic.id);
                });
                grid.appendChild(b);
            });
        }).catch(function() {
            p.classList.add('kp-empty');
        });
    }

    // Exit: keep the drawing, then close the app (back to the launcher / ES-DE).
    function installExitButton() {
        var btn = document.getElementById('exit');
        if (!btn) return;
        if (!bridge || !bridge.exit) {
            btn.style.display = 'none';
            return;
        }
        btn.addEventListener('click', function() {
            KiddoPaint.Sounds.mainmenu();
            KiddoPaint.Display.saveToLocalStorage();
            bridge.exit();
        });
    }

    function installOpenButton() {
        var btn = document.getElementById('open');
        if (!btn) return;
        btn.addEventListener('click', function() {
            KiddoPaint.Sounds.mainmenu();
            openPicker();
        });
    }


    // ---------- multi-step undo / redo ----------
    // Upstream keeps one snapshot and toggles. Keep a real history instead: every change
    // already calls Display.saveUndo() first, so that's where the stack grows.
    // Tap the Undo Guy = back one step, double-tap = forward one step.
    var HISTORY_MAX = 30;
    var history = {
        past: [], // Promise<Blob> PNG snapshots, kept outside the JS heap
        future: [],
        restoring: null, // snapshot being drawn onto the canvas
        token: 0
    };

    function snap() {
        // toBlob copies the pixels now and encodes in the background
        return new Promise(function(res) {
            KiddoPaint.Display.main_canvas.toBlob(res, 'image/png');
        });
    }

    function currentSnapshot() {
        return history.restoring || snap();
    }

    function showSnapshot(snapshot, doClearMain) {
        var token = ++history.token;
        history.restoring = snapshot;
        snapshot.then(function(blob) {
            return createImageBitmap(blob);
        }).then(function(bmp) {
            if (token !== history.token) return; // a newer undo/redo won
            if (doClearMain) KiddoPaint.Display.clearMain();
            KiddoPaint.Display.main_context.drawImage(bmp, 0, 0);
            history.restoring = null;
            KiddoPaint.Display.saveToLocalStorage();
        });
    }

    function installHistory() {
        KiddoPaint.Display.saveUndo = function() {
            if (!KiddoPaint.Display.undoOn) return false;
            history.past.push(currentSnapshot());
            if (history.past.length > HISTORY_MAX) history.past.shift();
            history.future.length = 0;
            KiddoPaint.Display.undoData = true; // upstream checks this is truthy
            return true;
        };
        KiddoPaint.Display.undo = function(doClearMain) {
            if (doClearMain === undefined) doClearMain = true;
            if (!history.past.length) return false;
            history.future.push(currentSnapshot());
            showSnapshot(history.past.pop(), doClearMain);
            return true;
        };
        KiddoPaint.Display.redo = function(doClearMain) {
            if (doClearMain === undefined) doClearMain = true;
            if (!history.future.length) return false;
            history.past.push(currentSnapshot());
            showSnapshot(history.future.pop(), doClearMain);
            return true;
        };
    }

    var DOUBLE_TAP_MS = 320;
    var undoTapTimer = null;

    function nudge(el) {
        if (!el) return;
        el.classList.remove('kp-nope');
        void el.offsetWidth;
        el.classList.add('kp-nope');
    }

    function undoGuyTap() {
        var guy = document.getElementById('undo');
        if (undoTapTimer) { // second tap: forward
            clearTimeout(undoTapTimer);
            undoTapTimer = null;
            KiddoPaint.Sounds.mainmenu();
            if (KiddoPaint.Display.redo(!KiddoPaint.Current.modifiedAlt)) {
                KiddoPaint.Sounds.submenuoption();
            } else {
                nudge(guy);
            }
            return;
        }
        undoTapTimer = setTimeout(function() { // single tap: back
            undoTapTimer = null;
            KiddoPaint.Sounds.mainmenu();
            if (KiddoPaint.Display.undo(!KiddoPaint.Current.modifiedAlt)) {
                KiddoPaint.Sounds.oops();
            } else {
                nudge(guy);
            }
        }, DOUBLE_TAP_MS);
    }

    function installUndoGuy() {
        var old = document.getElementById('undo');
        if (!old) return;
        var guy = old.cloneNode(true); // drops upstream's single-step listener
        old.parentNode.replaceChild(guy, old);
        guy.addEventListener('click', function(e) {
            e.preventDefault();
            undoGuyTap();
        });
    }

    // ---------- show which tool is selected ----------
    var TOOL_IDS = ['pencil', 'line', 'square', 'circle', 'brush', 'jumble', 'flood',
        'eraser', 'alphabet', 'stamp', 'truck'
    ];
    var ACTION_IDS = ['save', 'open', 'undo', 'exit'];
    var currentSubmenu = null;
    var subSelection = {}; // submenu name -> {groupIndex: buttonIndex}

    function markMainTool(id) {
        TOOL_IDS.forEach(function(t) {
            var b = document.getElementById(t);
            if (b) b.classList.toggle('kp-selected', t === id);
        });
    }

    // Spacer buttons split a submenu into independent choices (e.g. pencil size vs texture).
    function submenuGroups(container) {
        var groups = [
            []
        ];
        Array.prototype.forEach.call(container.children, function(b) {
            if (b.classList.contains('invisible')) groups.push([]);
            else groups[groups.length - 1].push(b);
        });
        return groups.filter(function(g) {
            return g.length;
        });
    }

    function paintSubSelection(container, name) {
        var sel = subSelection[name] || {};
        submenuGroups(container).forEach(function(g, gi) {
            g.forEach(function(b, bi) {
                b.classList.toggle('kp-selected', sel[gi] === bi);
            });
        });
    }

    function installSelectionHighlight() {
        TOOL_IDS.forEach(function(id) {
            var b = document.getElementById(id);
            if (b) b.addEventListener('mousedown', function() {
                markMainTool(id);
            });
        });
        ACTION_IDS.forEach(function(id) {
            var b = document.getElementById(id);
            if (!b) return;
            b.addEventListener('pointerdown', function() {
                b.classList.add('kp-pressed');
            });
            ['pointerup', 'pointercancel', 'pointerleave'].forEach(function(t) {
                b.addEventListener(t, function() {
                    b.classList.remove('kp-pressed');
                });
            });
        });
        markMainTool('pencil');

        var origShow = window.show_generic_submenu;
        window.show_generic_submenu = function(name) {
            currentSubmenu = name;
            // upstream picks the first option itself for these
            if (!subSelection[name] && (name === 'wackybrush' || name === 'sprites')) {
                subSelection[name] = {
                    0: 0
                };
            }
            var r = origShow.apply(this, arguments);
            paintSubSelection(document.getElementById('genericsubmenu'), name);
            return r;
        };

        // taps inside the option bar (generic submenu + letters)
        var bars = [document.getElementById('genericsubmenu'), document.getElementById('alphabettoolbar')];
        bars.forEach(function(bar) {
            if (!bar) return;
            bar.addEventListener('click', function(e) {
                var b = e.target.closest('button');
                if (!b || b.classList.contains('invisible') || !bar.contains(b)) return;
                var name = bar.id === 'alphabettoolbar' ? '__alphabet' : currentSubmenu;
                var groups = submenuGroups(bar);
                for (var gi = 0; gi < groups.length; gi++) {
                    var bi = groups[gi].indexOf(b);
                    if (bi !== -1) {
                        subSelection[name] = subSelection[name] || {};
                        subSelection[name][gi] = bi;
                    }
                }
                paintSubSelection(bar, name);
            }, true);
        });
        // letters use mousedown upstream; mirror it so the highlight lands immediately
        var letters = document.getElementById('alphabettoolbar');
        if (letters) letters.addEventListener('mousedown', function(e) {
            var b = e.target.closest('button');
            if (!b || b.id === 'alnext') return;
            Array.prototype.forEach.call(letters.querySelectorAll('button'), function(x) {
                x.classList.toggle('kp-selected', x === b);
            });
        }, true);
        // pencil starts on the middle size and solid ink, both shown selected
        KiddoPaint.Tools.Pencil.size = 9;
        subSelection.pencil = {
            0: 2,
            1: 0
        };
        paintSubSelection(document.getElementById('genericsubmenu'), 'pencil');
    }

    window.kidpix_android_boot = function() {
        document.documentElement.classList.add('kidpix-android');
        sizeCanvasToScreen();
        patchSplash();
        init_kiddo_paint();
        guardToolbarFromPalms();
        installOpenButton();
        installExitButton();
        installHistory();
        installUndoGuy();
        installSelectionHighlight();
    };

    // test hook: lets instrumentation read state without poking internals
    window.__kidpixAndroid = {
        state: state,
        openPicker: openPicker,
        history: history,
        undoGuyTap: undoGuyTap,
        loadPicture: loadPicture,
        toolName: function() {
            return toolName(KiddoPaint.Current.tool);
        }
    };
})();
