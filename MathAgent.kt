package io.shubham0204.smollmandroid.llm

import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.model.chat.ChatModel
import org.bsc.langgraph4j.GraphStateException
import org.bsc.langgraph4j.multi_agent.lc4j.AbstractAgentExecutor

// Step 1: Define the tools for the Math Agent
class MathTools {
    @Tool("Adds two integers (a + b)")
    fun add(a: Int, b: Int): Int {
        println("MathAgent: Adding $a and $b")
        return a + b
    }

    @Tool("Subtracts the second integer from the first (a - b)")
    fun subtract(a: Int, b: Int): Int {
        println("MathAgent: Subtracting $b from $a")
        return a - b
    }

    @Tool("Multiplies two integers (a * b)")
    fun multiply(a: Int, b: Int): Int {
        println("MathAgent: Multiplying $a and $b")
        return a * b
    }

    @Tool("Divides the first integer by the second (a / b)")
    fun divide(a: Int, b: Int): Int {
        println("MathAgent: Dividing $a by $b")
        if (b == 0) return 0
        return a / b
    }
}

// Step 2: Define the Math Agent, which is a specialized AgentExecutor
class MathAgent(builder: Builder) : AbstractAgentExecutor<MathAgent.Builder>(builder) {

    class Builder : AbstractAgentExecutor.Builder<Builder>() {
        @Throws(GraphStateException::class)
        fun build(chatModel: ChatModel): MathAgent {
            // A. Configure the internal AgentExecutor via the delegate
            chatModel(chatModel)
            systemMessage("You are a math expert. You must use the provided tools to solve mathematical problems. Do not answer directly.")
            toolFromObject(MathTools())

            // B. Configure the external "tool" appearance for the supervisor agent
            name("math_agent")
            description("Use this agent for any mathematical calculations, like addition, subtraction, multiplication, or division.")
            singleParameter("The mathematical question or expression to be solved.")

            return MathAgent(this)
        }
    }
}
