#!/usr/bin/env python3
"""Renders the pictures of the SmartArt picker: one per SmartArt layout sdkjs has a definition of.

web-apps draws them in the browser with sdkjs' SmartArtPreviewDrawer, on a canvas; here the engine
draws what that builds in place of a slide, and the layout is cut out of the page.
They go to libresources' drawable-nodpi as editors_smartart_<definition file>.webp, which
tools/web-apps-icons.py puts into the picker's catalog.

    RUN_ENGINE="<eo-render command line with EO_REPL=1, {doc} for the document>" \\
        tools/smartart-previews.py <sdkjs checkout> <empty presentation>

e.g. from the MobileEditors checkout, with the engine's host image (build/README.md, target engine-host):

    RUN_ENGINE="docker run -i --rm -v $PWD:/src -w /src -e EO_REPL=1 ghcr.io/euro-office/editors-engine-host \\
        eo-render /package/bin {doc} build/work/smartart 1" editors-android/tools/smartart-previews.py sdkjs \\
        document-templates/new/en-US/new.pptx

Needs Pillow. The layouts are Ascensio System SIA's definitions, drawn by sdkjs (AGPL).
"""
import json
import os
import re
import shlex
import shutil
import subprocess
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLES = os.path.join(ROOT, "libresources/src/main/res/drawable-nodpi")
# The longer side of the pictures, in pixels; the picker shows them at about 64 dp
SIZE = 192
PIXELS_PER_MM = 96 / 25.4


def snake(name):
    name = re.sub(r"([a-z])([A-Z0-9])", r"\1_\2", name)
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


class Engine:
    """eo-render's REPL: one line of JavaScript in, one "@@" result line out."""

    def __init__(self, command, document):
        args = [arg.replace("{doc}", document) for arg in shlex.split(command)]
        self.process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.read()

    def read(self):
        for line in self.process.stdout:
            if line.startswith("@@"):
                return line[2:].rstrip("\n")
        raise RuntimeError("the engine ended")

    def run(self, script):
        self.process.stdin.write(script.replace("\n", " ") + "\n")
        self.process.stdin.flush()
        return self.read()

    def close(self):
        self.process.stdin.close()
        self.process.wait()


def main(sdkjs, presentation):
    command = os.environ.get("RUN_ENGINE")
    if not command:
        sys.exit("RUN_ENGINE is not set (see the script's description)")
    cache = open(os.path.join(sdkjs, "common/SmartArts/SmartArtCache.js"), encoding="utf-8").read()
    layouts = re.findall(r'c_oAscSmartArtTypesToNameBinRelationShip\[Asc\.c_oAscSmartArtTypes\.(\w+)\] = "([^"]+)"', cache)

    work = os.path.join("build", "work", "smartart")
    os.makedirs(work, exist_ok=True)
    document = os.path.join(work, "empty.pptx")
    shutil.copyfile(presentation, document)
    os.makedirs(DRAWABLES, exist_ok=True)
    engine = Engine(command, document)
    # The slide draws the layout as sdkjs builds it for its previews (SmartArtPreviewDrawer: in a
    # square, lines in place of the texts), on a square page of that size
    engine.run("""
        var presentation = Asc.editor.WordControl.m_oLogicDocument, drawer = Asc.editor.smartArtPreviewManager;
        var size = drawer.SMARTART_PREVIEW_SIZE_MM, drawPage = presentation.DrawPage;
        presentation.GetWidthMM = presentation.GetHeightMM = function () { return size; };
        presentation.DrawPage = function (page, graphics) {
            graphics.isSmartArtPreviewDrawer = true;
            graphics.m_oContext = {};
            var drawHorLine = graphics.drawHorLine;
            graphics.drawHorLine = function () {
                this.b_color1(0, 0, 0, 255);
                this.p_color(0, 0, 0, 255);
                return drawHorLine.apply(this, arguments);
            };
            window.eoPreview.draw(graphics);
        };
        window.eoPreviewOf = function (type, file) {
            var directory = window.native.GetFontsDirectory() + "/SmartArts", cache = AscCommon.g_oBinarySmartArts;
            if (!cache.drawingBin.bin)
                cache.initDrawingFromBin(window.native.GetFileBinary(directory + "/SmartArtDrawing/SmartArtDrawings.bin"));
            if (!cache.dataBin[type])
                cache.initDataFromBin(type, window.native.GetFileBinary(directory + "/SmartArtData/" + file + ".bin"));
            var smartArt = window.eoPreview = drawer.getSmartArt(type);
            var bounds = smartArt.spTree[0].bounds;
            return JSON.stringify([bounds.x, bounds.y, bounds.x + bounds.w, bounds.y + bounds.h, size]);
        };
        ''""")
    failed = []
    for type_name, file_name in layouts:
        try:
            left, top, right, bottom, size = json.loads(engine.run(
                "eoPreviewOf(Asc.c_oAscSmartArtTypes.%s, %s)" % (type_name, json.dumps(file_name))))
        except ValueError:
            failed.append(file_name)
            continue
        page = os.path.join(work, "preview.png")
        engine.run("!render 0 %s" % page)
        # The layout with a margin, square if it nearly is
        margin = 0.04 * size
        box = [max(0, left - margin), max(0, top - margin), min(size, right + margin), min(size, bottom + margin)]
        box = tuple(round(v * PIXELS_PER_MM) for v in box)
        picture = Image.open(page).convert("RGB").crop(box)
        picture.thumbnail((SIZE, SIZE), Image.LANCZOS)
        picture.save(os.path.join(DRAWABLES, "editors_smartart_%s.webp" % snake(file_name)), quality=85)
    engine.close()
    print("%d pictures in %s" % (len(layouts) - len(failed), DRAWABLES))
    if failed:
        print("not inserted:", ", ".join(failed))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
