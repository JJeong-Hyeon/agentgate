import json

from app.llm.providers import LlmProviderConfig, load_llm_providers, resolve_model


def test_load_llm_providers_from_config_file(tmp_path):
    config = tmp_path / "providers.json"
    config.write_text(json.dumps({"gemini": {"base_url": "https://gemini/v1", "api_key": "k"}}))

    providers = load_llm_providers(str(config))

    assert providers["gemini"].base_url == "https://gemini/v1"
    assert providers["gemini"].api_key == "k"
    assert load_llm_providers("") == {}


def test_resolve_model_routes_a_registered_prefix():
    providers = {"gemini": LlmProviderConfig(base_url="https://gemini/v1", api_key="k")}

    model, provider = resolve_model("gemini@gemini-2.5-flash", providers)

    assert model == "gemini-2.5-flash"
    assert provider is providers["gemini"]


def test_resolve_model_leaves_a_plain_model_name_alone():
    assert resolve_model("qwen2.5:7b", {}) == ("qwen2.5:7b", None)
    assert resolve_model(None, {}) == (None, None)


def test_resolve_model_leaves_an_unregistered_prefix_alone():
    # "ollama" isn't registered, so the whole string is a (wrong) model name for the default
    # provider rather than silently guessing which provider was meant.
    model, provider = resolve_model("ollama@qwen2.5:7b", {})

    assert model == "ollama@qwen2.5:7b"
    assert provider is None


def test_resolve_model_does_not_mistake_an_ollama_tag_for_a_provider():
    providers = {"gemini": LlmProviderConfig(base_url="https://gemini/v1")}

    assert resolve_model("qwen3:14b-q4_K_M", providers) == ("qwen3:14b-q4_K_M", None)
