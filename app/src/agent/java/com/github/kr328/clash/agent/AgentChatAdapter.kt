package com.github.kr328.clash.agent

import android.content.Context
import android.graphics.Color
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.R
import com.github.kr328.clash.agent.model.AgentConversationMessage
import com.github.kr328.clash.agent.model.AgentMessageRole
import com.google.android.material.card.MaterialCardView
import io.noties.markwon.Markwon
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Chat adapter optimized for a single message that grows while the model streams.
 *
 * Stable IDs and payload updates keep RecyclerView from recreating the bubble. Markdown
 * parsing runs away from the main thread and each holder coalesces pending work, so an old
 * partial response can never replace a newer one.
 */
class AgentChatAdapter(
    private val context: Context,
    val messages: MutableList<AgentConversationMessage>,
    private val onContentRendered: (String) -> Unit = {},
) : RecyclerView.Adapter<AgentChatAdapter.Holder>(), Closeable {
    private val markwon = Markwon.create(context)
    private val markdownExecutor = Executors.newSingleThreadExecutor()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.agent_message_card)
        val text: TextView = view.findViewById(R.id.agent_message_text)
        val revision = AtomicLong()
        val renderScheduled = AtomicBoolean()
        val pendingRender = AtomicReference<RenderRequest?>()
    }

    data class RenderRequest(
        val revision: Long,
        val messageId: String,
        val markdown: String,
    )

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_agent_message, parent, false)
    )

    override fun getItemCount(): Int = messages.size

    override fun getItemId(position: Int): Long = messages[position].id.toStableLong()

    override fun onBindViewHolder(holder: Holder, position: Int) {
        bindMessage(holder, messages[position], fullBind = true)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CONTENT)) {
            bindMessage(holder, messages[position], fullBind = false)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.revision.incrementAndGet()
        holder.pendingRender.set(null)
        super.onViewRecycled(holder)
    }

    private fun bindMessage(holder: Holder, message: AgentConversationMessage, fullBind: Boolean) {
        if (fullBind) {
            val mine = message.role == AgentMessageRole.USER
            val params = holder.card.layoutParams as FrameLayout.LayoutParams
            params.gravity = if (mine) Gravity.END else Gravity.START
            holder.card.layoutParams = params

            val background = when {
                message.isError -> resolve(com.google.android.material.R.attr.colorError, Color.RED)
                mine -> resolve(com.google.android.material.R.attr.colorPrimary, Color.DKGRAY)
                else -> resolve(com.google.android.material.R.attr.colorSurface, Color.WHITE)
            }
            val foreground = when {
                message.isError -> resolve(com.google.android.material.R.attr.colorOnError, Color.WHITE)
                mine -> resolve(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
                else -> resolve(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            }
            holder.card.setCardBackgroundColor(background)
            holder.card.strokeColor = if (mine || message.isError) background else foreground.withAlpha(32)
            holder.text.setTextColor(foreground)
        }
        enqueueMarkdown(holder, message)
    }

    private fun enqueueMarkdown(holder: Holder, message: AgentConversationMessage) {
        val request = RenderRequest(
            revision = holder.revision.incrementAndGet(),
            messageId = message.id,
            markdown = message.content,
        )
        holder.pendingRender.set(request)
        if (holder.renderScheduled.compareAndSet(false, true)) {
            markdownExecutor.execute { drainMarkdown(holder) }
        }
    }

    private fun drainMarkdown(holder: Holder) {
        while (true) {
            val request = holder.pendingRender.getAndSet(null)
            if (request == null) {
                holder.renderScheduled.set(false)
                if (holder.pendingRender.get() != null && holder.renderScheduled.compareAndSet(false, true)) {
                    continue
                }
                return
            }

            val rendered: Spanned? = runCatching { markwon.toMarkdown(request.markdown) }.getOrNull()
            holder.text.post {
                if (holder.revision.get() != request.revision) return@post
                if (rendered != null) {
                    markwon.setParsedMarkdown(holder.text, rendered)
                } else {
                    holder.text.text = request.markdown
                }
                onContentRendered(request.messageId)
            }
        }
    }

    fun append(message: AgentConversationMessage): Int {
        messages += message
        val position = messages.lastIndex
        notifyItemInserted(position)
        return position
    }

    fun replace(position: Int, message: AgentConversationMessage, streaming: Boolean = false) {
        if (position !in messages.indices) return
        messages[position] = message
        if (streaming) notifyItemChanged(position, PAYLOAD_CONTENT) else notifyItemChanged(position)
    }

    fun clear() {
        val count = messages.size
        messages.clear()
        if (count > 0) notifyItemRangeRemoved(0, count)
    }

    override fun close() {
        markdownExecutor.shutdownNow()
    }

    private fun resolve(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun String.toStableLong(): Long = runCatching {
        UUID.fromString(this).let { it.mostSignificantBits xor it.leastSignificantBits }
    }.getOrElse { hashCode().toLong() }

    private fun Int.withAlpha(alpha: Int): Int = Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    private companion object {
        val PAYLOAD_CONTENT = Any()
    }
}
