// Renders Task Baba's original app icon (a calm, smiling monk) to a 1024×1024 PNG with CoreGraphics.
// Usage: swift MonkIcon.swift <output.png>
// The artwork is drawn from simple shapes here, so it is original and freely replaceable.

import AppKit
import CoreGraphics

let size: CGFloat = 1024
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "icon_1024.png"

func rgb(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: a)
}

let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: Int(size), height: Int(size), bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
// Flip so y grows downward, which makes the face coordinates easier to read.
ctx.translateBy(x: 0, y: size)
ctx.scaleBy(x: 1, y: -1)

// macOS icon grid: ~824pt rounded square centred in 1024.
let inset: CGFloat = 100
let tile = CGRect(x: inset, y: inset, width: size - 2 * inset, height: size - 2 * inset)
let tilePath = CGPath(roundedRect: tile, cornerWidth: 185, cornerHeight: 185, transform: nil)

// Soft drop shadow under the tile.
ctx.saveGState()
ctx.setShadow(offset: CGSize(width: 0, height: 12), blur: 28, color: rgb(0x000000, 0.28))
ctx.addPath(tilePath); ctx.setFillColor(rgb(0xFBF1E2)); ctx.fillPath()
ctx.restoreGState()

ctx.saveGState()
ctx.addPath(tilePath); ctx.clip()

// Warm background with a gentle vertical gradient.
let bg = CGGradient(colorsSpace: cs, colors: [rgb(0xFFF6E8), rgb(0xF7DDBB)] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(bg, start: CGPoint(x: 0, y: tile.minY), end: CGPoint(x: 0, y: tile.maxY), options: [])

let cx = size / 2

// Halo.
ctx.setStrokeColor(rgb(0xE07A1F, 0.35)); ctx.setLineWidth(22)
ctx.strokeEllipse(in: CGRect(x: cx - 250, y: 175, width: 500, height: 500))

// Saffron robe (shoulders) with a draped fold.
ctx.setFillColor(rgb(0xE07A1F))
let robe = CGMutablePath()
robe.move(to: CGPoint(x: tile.minX - 10, y: tile.maxY + 10))
robe.addCurve(to: CGPoint(x: cx, y: 640), control1: CGPoint(x: 230, y: 700), control2: CGPoint(x: 360, y: 640))
robe.addCurve(to: CGPoint(x: tile.maxX + 10, y: tile.maxY + 10), control1: CGPoint(x: 664, y: 640), control2: CGPoint(x: 794, y: 700))
robe.closeSubpath()
ctx.addPath(robe); ctx.fillPath()
ctx.setFillColor(rgb(0xC9631A))
let fold = CGMutablePath()
fold.move(to: CGPoint(x: cx - 150, y: 668))
fold.addCurve(to: CGPoint(x: cx + 210, y: tile.maxY + 10), control1: CGPoint(x: cx - 40, y: 720), control2: CGPoint(x: cx + 120, y: 820))
fold.addLine(to: CGPoint(x: cx + 90, y: tile.maxY + 10))
fold.addCurve(to: CGPoint(x: cx - 150, y: 668), control1: CGPoint(x: cx + 30, y: 830), control2: CGPoint(x: cx - 80, y: 740))
fold.closeSubpath()
ctx.addPath(fold); ctx.fillPath()

// Neck.
ctx.setFillColor(rgb(0xD9A27A))
ctx.fill(CGRect(x: cx - 55, y: 560, width: 110, height: 95))

// Head (shaven, round).
let head = CGRect(x: cx - 185, y: 225, width: 370, height: 390)
ctx.setFillColor(rgb(0xE8B48C))
ctx.fillEllipse(in: head)
// Ears.
ctx.fillEllipse(in: CGRect(x: head.minX - 34, y: 395, width: 70, height: 100))
ctx.fillEllipse(in: CGRect(x: head.maxX - 36, y: 395, width: 70, height: 100))
// Subtle highlight on the crown.
ctx.setFillColor(rgb(0xFFFFFF, 0.18))
ctx.fillEllipse(in: CGRect(x: cx - 110, y: 255, width: 140, height: 80))

// Tilak: three soft horizontal lines with a saffron dot.
ctx.setStrokeColor(rgb(0xFFFFFF, 0.85)); ctx.setLineWidth(9); ctx.setLineCap(.round)
for (i, w) in [90.0, 110.0, 90.0].enumerated() {
    let y = 318 + CGFloat(i) * 20
    ctx.move(to: CGPoint(x: cx - CGFloat(w) / 2, y: y)); ctx.addLine(to: CGPoint(x: cx + CGFloat(w) / 2, y: y))
}
ctx.strokePath()
ctx.setFillColor(rgb(0xD9480F))
ctx.fillEllipse(in: CGRect(x: cx - 14, y: 324, width: 28, height: 28))

// Closed, content eyes (gentle arcs).
ctx.setStrokeColor(rgb(0x3A2A20)); ctx.setLineWidth(14)
for dx in [-78.0, 78.0] {
    let ex = cx + CGFloat(dx)
    ctx.move(to: CGPoint(x: ex - 38, y: 430))
    ctx.addQuadCurve(to: CGPoint(x: ex + 38, y: 430), control: CGPoint(x: ex, y: 458))
}
ctx.strokePath()
// Raised, knowing eyebrow on one side (a hint of Savage Baba).
ctx.setLineWidth(11)
ctx.move(to: CGPoint(x: cx - 120, y: 392)); ctx.addQuadCurve(to: CGPoint(x: cx - 40, y: 390), control: CGPoint(x: cx - 80, y: 375))
ctx.move(to: CGPoint(x: cx + 40, y: 378)); ctx.addQuadCurve(to: CGPoint(x: cx + 120, y: 386), control: CGPoint(x: cx + 80, y: 352))
ctx.strokePath()

// Cheeks.
ctx.setFillColor(rgb(0xE88A6A, 0.35))
ctx.fillEllipse(in: CGRect(x: cx - 150, y: 470, width: 70, height: 40))
ctx.fillEllipse(in: CGRect(x: cx + 80, y: 470, width: 70, height: 40))

// Smile.
ctx.setStrokeColor(rgb(0x3A2A20)); ctx.setLineWidth(12)
ctx.move(to: CGPoint(x: cx - 55, y: 515))
ctx.addQuadCurve(to: CGPoint(x: cx + 55, y: 515), control: CGPoint(x: cx, y: 560))
ctx.strokePath()

// Rudraksha-style bead necklace.
ctx.setFillColor(rgb(0x7A3E1D))
for i in 0..<11 {
    let t = CGFloat(i) / 10
    let x = cx - 150 + t * 300
    let y = 690 + 60 * (1 - pow(2 * t - 1, 2))
    ctx.fillEllipse(in: CGRect(x: x - 15, y: y - 15, width: 30, height: 30))
}

ctx.restoreGState()

let image = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: image)
let png = rep.representation(using: .png, properties: [:])!
try! png.write(to: URL(fileURLWithPath: out))
print(out)
