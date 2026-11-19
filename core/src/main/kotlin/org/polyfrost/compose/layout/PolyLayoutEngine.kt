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
                val intrinsic = if (horizontal) node.intrinsicWidth else node.intrinsicHeight
                val padding = if (horizontal) node.style.padding.horizontal else node.style.padding.vertical
                if (intrinsic >= 0f) return intrinsic + padding
                val gap = node.style.gap
                val children = node.children
                var n = 0
                for (i in children.indices) if (children[i].style.positionMode != PolyPositionMode.Absolute) n++
                val gaps = if (n > 1) gap * (n - 1) else 0f
                val sums = if (horizontal) node.style.layoutType == LayoutType.Row else node.style.layoutType == LayoutType.Column
                var content = 0f
                var hasFill = false
                for (i in children.indices) {
                    val c = children[i]
                    if (c.style.positionMode == PolyPositionMode.Absolute) continue
                    val childSize = if (horizontal) c.style.width else c.style.height
                    if (childSize === PolySize.Fill) hasFill = true
                    val extent = if (childSize === PolySize.Fill) 0f else if (horizontal) c.width else c.height
                    val margin = if (horizontal) c.style.margin.horizontal else c.style.margin.vertical
                    content = if (sums) content + extent + margin else maxOf(content, extent + margin)
                }
                if (hasFill && content == 0f) return (parentSize).coerceAtLeast(padding)
                if (sums) content += gaps
                content + padding
            }
        }
    }

    private fun arrangeChildren(node: PolyNode) {
        val s = node.style
        val cx = node.x + s.padding.left
        val cy = node.y + s.padding.top
        val cw = (node.width - s.padding.horizontal).coerceAtLeast(0f)
        val ch = (node.height - s.padding.vertical).coerceAtLeast(0f)

        when (s.layoutType) {
            LayoutType.Row -> {
                var cursor = cx
                var placed = false
                val children = node.children
                for (i in children.indices) {
                    val child = children[i]
                    if (child.style.positionMode == PolyPositionMode.Absolute) {
                        child.x = node.x + child.style.x
                        child.y = node.y + child.style.y
                        arrangeChildren(child)
                        continue
                    }
                    if (placed) cursor += s.gap
                    placed = true
                    child.x = cursor + child.style.margin.left
                    child.y = cy + crossAxis(child.style.align, ch, child.height, child.style.margin, false)
                    arrangeChildren(child)
                    cursor += child.width + child.style.margin.horizontal
                }
            }
            LayoutType.Column -> {
                var cursor = cy
                var placed = false
                val children = node.children
                for (i in children.indices) {
                    val child = children[i]
                    if (child.style.positionMode == PolyPositionMode.Absolute) {
                        child.x = node.x + child.style.x
                        child.y = node.y + child.style.y
                        arrangeChildren(child)
                        continue
                    }
                    if (placed) cursor += s.gap
                    placed = true
                    child.x = cx + crossAxis(child.style.align, cw, child.width, child.style.margin, true)
                    child.y = cursor + child.style.margin.top
                    arrangeChildren(child)
                    cursor += child.height + child.style.margin.vertical
                }
            }
            LayoutType.Box -> {
                val children = node.children
                for (i in children.indices) {
                    val child = children[i]
                    if (child.style.positionMode == PolyPositionMode.Absolute) {
                        child.x = node.x + child.style.x
                        child.y = node.y + child.style.y
                    } else {
                        child.x = cx + crossAxis(child.style.align, cw, child.width, child.style.margin, true)
                        child.y = cy + crossAxis(child.style.align, ch, child.height, child.style.margin, false)
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
