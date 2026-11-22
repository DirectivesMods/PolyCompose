package org.polyfrost.compose.layout

import org.polyfrost.compose.node.PolyNode

public object PolyLayoutEngine {

    public fun layout(node: PolyNode, parentWidth: Float, parentHeight: Float) {
        measure(node, parentWidth, parentHeight)
        arrangeChildren(node)
    }

    private fun measure(node: PolyNode, parentWidth: Float, parentHeight: Float) {
        node.measureContent()
        val style = node.style
        val children = node.children

        val wrapWidth = style.width === PolySize.Wrap && node.intrinsicWidth < 0f
        val wrapHeight = style.height === PolySize.Wrap && node.intrinsicHeight < 0f
        if (!wrapWidth) node.width = resolveSize(style.width, parentWidth, node, horizontal = true)
        if (!wrapHeight) node.height = resolveSize(style.height, parentHeight, node, horizontal = false)

        val padH = style.padding.horizontal
        val padV = style.padding.vertical
        val provisionalW = ((if (wrapWidth) parentWidth else node.width) - padH).coerceAtLeast(0f)
        val provisionalH = ((if (wrapHeight) parentHeight else node.height) - padV).coerceAtLeast(0f)
        for (i in children.indices) measure(children[i], provisionalW, provisionalH)

        if (!wrapWidth && !wrapHeight) return
        if (wrapWidth) node.width = resolveSize(PolySize.Wrap, parentWidth, node, horizontal = true)
        if (wrapHeight) node.height = resolveSize(PolySize.Wrap, parentHeight, node, horizontal = false)

        val finalW = (node.width - padH).coerceAtLeast(0f)
        val finalH = (node.height - padV).coerceAtLeast(0f)
        if (finalW == provisionalW && finalH == provisionalH) return
        for (i in children.indices) {
            val child = children[i]
            val cw = child.style.width
            val ch = child.style.height
            val fillW = wrapWidth && (cw === PolySize.Fill || cw is PolySize.Percent)
            val fillH = wrapHeight && (ch === PolySize.Fill || ch is PolySize.Percent)
            if (fillW || fillH) measure(child, finalW, finalH)
        }
        if (wrapWidth) node.width = resolveSize(PolySize.Wrap, parentWidth, node, horizontal = true)
        if (wrapHeight) node.height = resolveSize(PolySize.Wrap, parentHeight, node, horizontal = false)
    }

    private fun resolveSize(size: PolySize, parentSize: Float, node: PolyNode, horizontal: Boolean): Float {
        return when (size) {
            is PolySize.Px -> size.value
            is PolySize.Percent -> parentSize * size.value
            PolySize.Fill -> parentSize
            PolySize.Wrap -> {
                val ns = node.style
                val intrinsic = if (horizontal) node.intrinsicWidth else node.intrinsicHeight
                val padding = if (horizontal) ns.padding.horizontal else ns.padding.vertical
                if (intrinsic >= 0f) return intrinsic + padding
                val gap = ns.gap
                val children = node.children
                var n = 0
                val sums = if (horizontal) ns.layoutType == LayoutType.Row else ns.layoutType == LayoutType.Column
                var content = 0f
                var hasFill = false
                for (i in children.indices) {
                    val c = children[i]
                    val cs = c.style
                    if (cs.positionMode == PolyPositionMode.Absolute) continue
                    n++
                    val childSize = if (horizontal) cs.width else cs.height
                    val fill = childSize === PolySize.Fill
                    if (fill) hasFill = true
                    val extent = if (fill) 0f else if (horizontal) c.width else c.height
                    val margin = if (horizontal) cs.margin.horizontal else cs.margin.vertical
                    content = if (sums) content + extent + margin else maxOf(content, extent + margin)
                }
                if (hasFill && content == 0f) return (parentSize).coerceAtLeast(padding)
                val gaps = if (n > 1) gap * (n - 1) else 0f
                if (sums) content += gaps
                content + padding
            }
        }
    }

    private fun arrangeChildren(node: PolyNode) {
        val s = node.style
        val children = node.children
        if (children.isEmpty()) return
        val pad = s.padding
        val nx = node.x
        val ny = node.y
        val cx = nx + pad.left
        val cy = ny + pad.top
        val cw = (node.width - pad.horizontal).coerceAtLeast(0f)
        val ch = (node.height - pad.vertical).coerceAtLeast(0f)

        when (s.layoutType) {
            LayoutType.Row -> {
                var cursor = cx
                var placed = false
                val gap = s.gap
                for (i in children.indices) {
                    val child = children[i]
                    val cs = child.style
                    if (cs.positionMode == PolyPositionMode.Absolute) {
                        child.x = nx + cs.x
                        child.y = ny + cs.y
                        arrangeChildren(child)
                        continue
                    }
                    if (placed) cursor += gap
                    placed = true
                    val margin = cs.margin
                    child.x = cursor + margin.left
                    child.y = cy + crossAxis(cs.align, ch, child.height, margin, false)
                    arrangeChildren(child)
                    cursor += child.width + margin.horizontal
                }
            }
            LayoutType.Column -> {
                var cursor = cy
                var placed = false
                val gap = s.gap
                for (i in children.indices) {
                    val child = children[i]
                    val cs = child.style
                    if (cs.positionMode == PolyPositionMode.Absolute) {
                        child.x = nx + cs.x
                        child.y = ny + cs.y
                        arrangeChildren(child)
                        continue
                    }
                    if (placed) cursor += gap
                    placed = true
                    val margin = cs.margin
                    child.x = cx + crossAxis(cs.align, cw, child.width, margin, true)
                    child.y = cursor + margin.top
                    arrangeChildren(child)
                    cursor += child.height + margin.vertical
                }
            }
            LayoutType.Box -> {
                for (i in children.indices) {
                    val child = children[i]
                    val cs = child.style
                    if (cs.positionMode == PolyPositionMode.Absolute) {
                        child.x = nx + cs.x
                        child.y = ny + cs.y
                    } else {
                        val margin = cs.margin
                        child.x = cx + crossAxis(cs.align, cw, child.width, margin, true)
                        child.y = cy + crossAxis(cs.align, ch, child.height, margin, false)
                    }
                    arrangeChildren(child)
                }
            }
        }
    }

    private fun crossAxis(align: PolyAlign, container: Float, child: Float, margin: PolyInsets, horizontal: Boolean): Float {
        return if (horizontal) when (align) {
            PolyAlign.TopLeft, PolyAlign.Left, PolyAlign.BottomLeft -> margin.left
            PolyAlign.Top, PolyAlign.Center, PolyAlign.Bottom -> (container - child) / 2f
            PolyAlign.TopRight, PolyAlign.Right, PolyAlign.BottomRight -> container - child - margin.right
        } else when (align) {
            PolyAlign.TopLeft, PolyAlign.Top, PolyAlign.TopRight -> margin.top
            PolyAlign.Left, PolyAlign.Center, PolyAlign.Right -> (container - child) / 2f
            PolyAlign.BottomLeft, PolyAlign.Bottom, PolyAlign.BottomRight -> container - child - margin.bottom
        }
    }
}
