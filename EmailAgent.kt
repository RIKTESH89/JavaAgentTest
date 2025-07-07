package io.shubham0204.smollmandroid.llm

import dev.langchain4j.model.chat.ChatModel
import org.bsc.langgraph4j.multi_agent.lc4j.AbstractAgentService

// Define the Email Agent using the simpler AbstractAgentService
class EmailAgent(builder: Builder) : AbstractAgentService<EmailAgent.Builder>(builder) {

    class Builder : AbstractAgentService.Builder<Builder>() {
        fun build(chatModel: ChatModel): EmailAgent {
            // A. Configure the internal AiService agent
            chatModel(chatModel)
            systemMessage("You are an expert at writing professional and concise emails.")

            // B. Configure the external "tool" appearance for the supervisor agent
            name("email_agent")
            description("Use this agent to write an email to a person based on a given topic or context.")
            singleParameter("The name of the person and the topic for the email.")

            return EmailAgent(this)
        }
    }
}
