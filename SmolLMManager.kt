/*
 * Copyright (C) 2025 Shubham Panchal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shubham0204.smollmandroid.llm

import android.util.Log
import com.example.llama.localclient.SmolLMInferenceEngine
import com.smith.lai.langgraph4j_android_adapter.BuildConfig
import com.smith.lai.langgraph4j_android_adapter.httpclient.OkHttpClientBuilder
import com.smith.lai.langgraph4j_android_adapter.localclient.LocalLLMInferenceEngine
import dev.langchain4j.agent.tool.ToolSpecifications
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import io.shubham0204.smollm.SmolLM
import io.shubham0204.smollmandroid.data.Chat
import io.shubham0204.smollmandroid.data.MessagesDB
import io.shubham0204.smollmandroid.llm.localclient.DummyTools
import io.shubham0204.smollmandroid.ui.screens.chat.ChatScreenViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bsc.langgraph4j.CompileConfig
import org.bsc.langgraph4j.CompiledGraph
import org.bsc.langgraph4j.RunnableConfig
import org.bsc.langgraph4j.StateGraph
import org.bsc.langgraph4j.agentexecutor.AgentExecutor
import org.bsc.langgraph4j.checkpoint.MemorySaver
import org.bsc.langgraph4j.multi_agent.lc4j.AgentHandoff
import org.koin.core.annotation.Single
import java.time.Duration
import kotlin.jvm.optionals.getOrNull
import kotlin.time.measureTime

private const val LOGTAG = "[SmolLMManager-Kt]"
private val LOGD: (String) -> Unit = { Log.d(LOGTAG, it) }

@Single
class SmolLMManager(
    private val messagesDB: MessagesDB,
) {
    private val instance = SmolLM()
    private var responseGenerationJob: Job? = null
    private var modelInitJob: Job? = null
    private var chat: Chat? = null
    private lateinit var viewModel: ChatScreenViewModel
    private var compiled_graph: CompiledGraph<AgentExecutor.State>? = null
    private val TEST_MODE = listOf("openai", "ollama", "local").get(0) // Default to openai for robust routing
    private var instanceWithTools: LocalLLMInferenceEngine? = null
    private var openai: OpenAiChatModel? = null
    private var ollama: OllamaChatModel? = null

    private var isInstanceLoaded = false
    var isInferenceOn = false

    data class SmolLMInitParams(
        val chat: Chat,
        val modelPath: String,
        val minP: Float,
        val temperature: Float,
        val storeChats: Boolean,
        val contextSize: Long,
        val chatTemplate: String,
        val nThreads: Int,
        val useMmap: Boolean,
        val useMlock: Boolean,
    )

    data class SmolLMResponse(
        val response: String,
        val generationSpeed: Float,
        val generationTimeSecs: Int,
        val contextLengthUsed: Int,
    )

    fun setViewModel(_viewModel: ChatScreenViewModel) {
        viewModel = _viewModel
    }

    // Renamed from resetGraph to be more descriptive of the new functionality
    fun setupAgentHandoff() {
        val model: ChatModel? = when (TEST_MODE) {
            "openai" -> {
                if (openai == null) {
                    val httpClientBuilder = OkHttpClientBuilder()
                    httpClientBuilder.connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofSeconds(120))
                    openai = OpenAiChatModel.builder()
                        .apiKey(BuildConfig.OPENAI_API_KEY)
                        .baseUrl("https://api.openai.com/v1")
                        .httpClientBuilder(httpClientBuilder)
                        .modelName("gpt-4o-mini") // Using a more capable model for routing
                        .temperature(0.0)
                        .maxTokens(2000)
                        .maxRetries(2)
                        .logRequests(true)
                        .logResponses(true)
                        .build()
                }
                openai
            }
            "ollama" -> {
                if (ollama == null) {
                    val httpClientBuilder = OkHttpClientBuilder()
                    httpClientBuilder.connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofSeconds(120))
                    ollama = OllamaChatModel.builder()
                        .baseUrl(BuildConfig.OLLAMA_URL)
                        .httpClientBuilder(httpClientBuilder)
                        .temperature(0.0)
                        .logRequests(true)
                        .logResponses(true)
                        .modelName("llama3.1:latest") // A capable model is needed for routing
                        .build();
                }
                ollama
            }
            else -> {
                val tools = DummyTools(viewModel) // Dummy tools for local model if needed
                if (instanceWithTools == null) {
                    instanceWithTools =
                        SmolLMInferenceEngine(_instance, ToolSpecifications.toolSpecificationsFrom(tools))
                } else {
                    instanceWithTools!!.reset()
                }
                instanceWithTools?.setTemplate(chat!!.chatTemplate)
                instanceWithTools
            }
        }

        // --- AGENT HANDOFF IMPLEMENTATION ---
        // 1. Create instances of our specialist agents
        val mathAgent = MathAgent.Builder().build(model!!)
        val emailAgent = EmailAgent.Builder().build(model)

        // 2. Use AgentHandoff to build the supervisor graph
        val supervisorGraph = AgentHandoff.builder()
            // The supervisor needs a brain to route tasks
            .chatModel(model)
            // Register the specialist agents as tools for the supervisor
            .agent(mathAgent)
            .agent(emailAgent)
            .build()

        // 3. Compile the supervisor graph with a memory saver
        val saver = MemorySaver()
        val compileConfig = CompileConfig.builder()
            .checkpointSaver(saver)
            .build()
        compiled_graph = supervisorGraph.compile(compileConfig)
        println("Agent Handoff Supervisor Graph initialized.")
    }

    fun create(
        initParams: SmolLMInitParams,
        onError: (Exception) -> Unit,
        onSuccess: () -> Unit,
    ) {
        try {
            modelInitJob =
                CoroutineScope(Dispatchers.Default).launch {
                    chat = initParams.chat
                    if (isInstanceLoaded) {
                        close()
                    }
                    instance.create(
                        initParams.modelPath,
                        initParams.minP,
                        initParams.temperature,
                        initParams.storeChats,
                        initParams.contextSize,
                        initParams.chatTemplate,
                        initParams.nThreads,
                        initParams.useMmap,
                        initParams.useMlock,
                    )
                    LOGD("Model loaded")
                    if (initParams.chat.systemPrompt.isNotEmpty()) {
                        instance.addSystemPrompt(initParams.chat.systemPrompt)
                        LOGD("System prompt added")
                    }
                    if (!initParams.chat.isTask) {
                        messagesDB.getMessagesForModel(initParams.chat.id).forEach { message ->
                            if (message.isUserMessage) {
                                instance.addUserMessage(message.message)
                                LOGD("User message added: ${message.message}")
                            } else {
                                instance.addAssistantMessage(message.message)
                                LOGD("Assistant message added: ${message.message}")
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        isInstanceLoaded = true
                        onSuccess()
                    }
                }
        } catch (e: Exception) {
            onError(e)
        }
    }

    fun getResponse(
        query: String,
        responseTransform: (String) -> String,
        onPartialResponseGenerated: (String) -> Unit,
        onSuccess: (SmolLMResponse) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        try {
            assert(chat != null) { "Please call SmolLMManager.create() first." }
            responseGenerationJob =
                CoroutineScope(Dispatchers.Default).launch {
                    isInferenceOn = true
                    var finalResponse = ""
                    val duration =
                        measureTime {
                            // Setup the new agent handoff graph
                            setupAgentHandoff()

                            val config = RunnableConfig.builder()
                                .threadId(chat!!.id.toString()) // Use chat ID for persistent memory
                                .build()

                            // The input is now just a single user message for the supervisor
                            val inputs = mapOf(
                                "messages" to listOf(UserMessage.from(query))
                            )

                            Log.i(LOGTAG, "Invoking supervisor with messages: $inputs")
                            val iterator = compiled_graph!!.stream(
                                inputs,
                                config
                            )

                            Log.i(LOGTAG, "[All Steps]")
                            iterator.forEach { step ->
                                Log.i(LOGTAG, "[${step.node()}] State: ${step.state().messages()}")
                                val lastMessage = step.state().lastMessage().getOrNull()

                                if (lastMessage != null) {
                                    val content = when (lastMessage) {
                                        is AiMessage -> {
                                            if (lastMessage.hasToolExecutionRequests()) {
                                                "Calling tool: ${lastMessage.toolExecutionRequests().joinToString { it.name() }}"
                                            } else {
                                                lastMessage.text()
                                            }
                                        }
                                        is ToolExecutionResultMessage -> "Tool Result for ${lastMessage.toolName()}: ${lastMessage.text()}"
                                        else -> null
                                    }
                                    if (content != null) {
                                        withContext(Dispatchers.Main) {
                                            onPartialResponseGenerated("...$content\n")
                                        }
                                    }
                                }

                                // Check for the final response at the end of the graph execution
                                if (step.node() == StateGraph.END) {
                                    step.state().finalResponse().getOrNull()?.let {
                                        Log.i(LOGTAG, "[${step.node()}] Final Graph output: $it")
                                        finalResponse = it
                                    }
                                }
                            }
                        }

                    finalResponse = responseTransform(finalResponse)
                    messagesDB.addAssistantMessage(chat!!.id, finalResponse)
                    withContext(Dispatchers.Main) {
                        isInferenceOn = false
                        onSuccess(
                            SmolLMResponse(
                                response = finalResponse,
                                generationSpeed = instance.getResponseGenerationSpeed(),
                                generationTimeSecs = duration.inWholeSeconds.toInt(),
                                contextLengthUsed = instance.getContextLengthUsed(),
                            ),
                        )
                    }
                }
        } catch (e: CancellationException) {
            isInferenceOn = false
            onCancelled()
        } catch (e: Exception) {
            isInferenceOn = false
            onError(e)
        }
    }

    fun stopResponseGeneration() {
        responseGenerationJob?.let { cancelJobIfActive(it) }
    }

    fun close() {
        stopResponseGeneration()
        modelInitJob?.let { cancelJobIfActive(it) }
        instance.close()
        isInstanceLoaded = false
    }

    private fun cancelJobIfActive(job: Job) {
        if (job.isActive) {
            job.cancel()
        }
    }
}
