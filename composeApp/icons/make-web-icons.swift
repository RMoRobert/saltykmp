import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// Rebuilds the Android adaptive launcher icon (background + foreground layers) as the flat raster
// icons the web app serves. Run it after the launcher icon changes:
//
//     swift composeApp/icons/make-web-icons.swift server/src/main/resources/static
//
// The two layers have to be composited here because an adaptive icon is never one bitmap on disk;
// the ic_launcher.png Android generates is only 192px and mostly padding. The crop is tighter than
// Android's own 72/108 visible area so the shaker still reads at 16px, and the corner radius
// stands in for the launcher's mask. macOS only: CoreGraphics does the compositing.
// Resolved from the script's own location so it runs from any working directory.
let SRC = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()                       // composeApp/icons
    .deletingLastPathComponent()                       // composeApp
    .appendingPathComponent("src/androidMain/res/mipmap-xxxhdpi").path
let OUT = CommandLine.arguments[1]
let CROP: CGFloat = 240      // of the 432 source canvas (Android's own visible area is 288)
let RADIUS: CGFloat = 0.22   // fraction of side

func load(_ name: String) -> CGImage {
    let url = URL(fileURLWithPath: "\(SRC)/\(name)") as CFURL
    guard let s = CGImageSourceCreateWithURL(url, nil), let i = CGImageSourceCreateImageAtIndex(s, 0, nil)
    else { fatalError("cannot load \(name)") }
    return i
}
let bg = load("ic_launcher_background.png")
let fg = load("ic_launcher_foreground.png")
let srcSide = CGFloat(bg.width)

func newCtx(_ side: Int) -> CGContext {
    guard let c = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { fatalError("ctx") }
    c.interpolationQuality = .high
    return c
}

/// Composite at `side`, optionally masked to a rounded square.
func compose(side: Int, rounded: Bool) -> CGImage {
    let s = CGFloat(side)
    let ctx = newCtx(side)
    if rounded {
        ctx.addPath(CGPath(roundedRect: CGRect(x: 0, y: 0, width: s, height: s),
                           cornerWidth: s * RADIUS, cornerHeight: s * RADIUS, transform: nil))
        ctx.clip()
    }
    // Map the centred CROP x CROP region of the source onto the whole canvas.
    let scale = s / CROP
    let offset = (srcSide - CROP) / 2 * scale
    let dst = CGRect(x: -offset, y: -offset, width: srcSide * scale, height: srcSide * scale)
    ctx.draw(bg, in: dst)
    ctx.draw(fg, in: dst)
    guard let img = ctx.makeImage() else { fatalError("compose") }
    return img
}

/// Supersample: build at 4x then reduce, so the mask edge is clean at small sizes.
func render(side: Int, rounded: Bool) -> CGImage {
    let big = compose(side: side * 4, rounded: rounded)
    let ctx = newCtx(side)
    ctx.draw(big, in: CGRect(x: 0, y: 0, width: CGFloat(side), height: CGFloat(side)))
    guard let img = ctx.makeImage() else { fatalError("render") }
    return img
}

func pngData(_ img: CGImage) -> Data {
    let out = NSMutableData()
    guard let d = CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil)
    else { fatalError("dest") }
    CGImageDestinationAddImage(d, img, nil)
    guard CGImageDestinationFinalize(d) else { fatalError("finalize") }
    return out as Data
}
func write(_ data: Data, _ name: String) {
    try! data.write(to: URL(fileURLWithPath: "\(OUT)/\(name)"))
    print("\(name)  \(data.count) bytes")
}

// -- PNGs -------------------------------------------------------------------
write(pngData(render(side: 192, rounded: true)),  "icon-192.png")
write(pngData(render(side: 32,  rounded: true)),  "favicon-32.png")
// iOS masks apple-touch-icon itself, so it gets the full square with no rounding.
write(pngData(render(side: 180, rounded: false)), "apple-touch-icon.png")

// -- favicon.ico (PNG-encoded 16/32/48 entries) ------------------------------
let icoSizes = [16, 32, 48]
let payloads = icoSizes.map { pngData(render(side: $0, rounded: true)) }
var ico = Data()
func u16(_ v: Int) -> Data { var x = UInt16(v).littleEndian; return Data(bytes: &x, count: 2) }
func u32(_ v: Int) -> Data { var x = UInt32(v).littleEndian; return Data(bytes: &x, count: 4) }
ico += u16(0) + u16(1) + u16(icoSizes.count)
var offset = 6 + 16 * icoSizes.count
for (i, size) in icoSizes.enumerated() {
    ico += Data([UInt8(size == 256 ? 0 : size), UInt8(size == 256 ? 0 : size), 0, 0])
    ico += u16(1) + u16(32) + u32(payloads[i].count) + u32(offset)
    offset += payloads[i].count
}
for p in payloads { ico += p }
write(ico, "favicon.ico")
