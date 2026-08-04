package com.github.kr328.clash.agent

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.github.kr328.clash.R
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.util.setOnInsertsChangedListener

class AgentScreenDesign(context: Context) : Design<Unit>(context) {
    override val root: View = LayoutInflater.from(context).inflate(R.layout.activity_agent, null, false)

    init {
        val header = root.findViewById<View>(R.id.agent_header)
        val composer = root.findViewById<View>(R.id.agent_composer)
        val density = context.resources.displayMetrics.density
        val headerHeight = (64 * density).toInt()
        val composerBottom = (10 * density).toInt()

        root.setOnInsertsChangedListener(adaptLandscape = false) { insets ->
            header.updateLayoutParams { height = headerHeight + insets.top }
            header.updatePadding(top = insets.top)
            composer.updatePadding(bottom = composerBottom + insets.bottom)
        }
    }
}
