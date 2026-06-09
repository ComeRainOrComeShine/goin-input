local gan_comment_map = nil

local function try_open(path)
  if path == nil or path == "" then
    return nil
  end
  return io.open(path, "r")
end

local function open_gan_comments()
  local names = {
    "gan_auxiliary_comments.tsv",
    "shared/gan_auxiliary_comments.tsv",
  }

  local shared_dir = os.getenv("RIME_SHARED_DATA_DIR")
  local user_dir = os.getenv("RIME_USER_DATA_DIR")
  if shared_dir ~= nil then
    table.insert(names, shared_dir .. "/gan_auxiliary_comments.tsv")
    table.insert(names, shared_dir .. "/shared/gan_auxiliary_comments.tsv")
  end
  if user_dir ~= nil then
    table.insert(names, user_dir .. "/gan_auxiliary_comments.tsv")
  end

  for _, path in ipairs(names) do
    local file = try_open(path)
    if file ~= nil then
      return file
    end
  end
  return nil
end

local function load_gan_comment_map()
  if gan_comment_map ~= nil then
    return gan_comment_map
  end

  gan_comment_map = {}
  local file = open_gan_comments()
  if file == nil then
    return gan_comment_map
  end

  for line in file:lines() do
    local word, code = line:match("^([^\t]+)\t(.+)$")
    if word ~= nil and code ~= nil and word ~= "" and code ~= "" then
      gan_comment_map[word] = code
    end
  end

  file:close()
  return gan_comment_map
end

function gan_comment_filter(input, env)
  local comments = load_gan_comment_map()
  for cand in input:iter() do
    local code = comments[cand.text]
    if code ~= nil then
      local new_cand = Candidate(cand.type, cand.start, cand._end, cand.text, "〔赣〕" .. code)
      new_cand.quality = cand.quality
      yield(new_cand)
    else
      yield(cand)
    end
  end
end
